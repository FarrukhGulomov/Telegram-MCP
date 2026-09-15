package io.tgmcp.consent;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.tgmcp.audit.AuditAction;
import io.tgmcp.audit.AuditService;
import io.tgmcp.chat.ChatActivation;
import io.tgmcp.chat.ChatParticipant;
import io.tgmcp.chat.ChatParticipantRepository;
import io.tgmcp.common.Ids;
import io.tgmcp.message.MessageRepository;
import io.tgmcp.telegram.gateway.TelegramAdapterKind;
import io.tgmcp.telegram.gateway.TelegramGateway;
import io.tgmcp.tenancy.AuthenticatedActor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes and records participant consent.
 *
 * <p>Coverage is evaluated over the *current* participant list, so a chat whose membership
 * has changed cannot stay covered by the consent of the people who used to be in it.
 */
@Service
public class ConsentService {

    private final ConsentRepository consentRepository;
    private final ChatParticipantRepository participantRepository;
    private final MessageRepository messageRepository;
    private final AuditService auditService;
    private final TelegramGateway gateway;
    private final Clock clock;

    public ConsentService(ConsentRepository consentRepository,
                          ChatParticipantRepository participantRepository,
                          MessageRepository messageRepository,
                          AuditService auditService,
                          TelegramGateway gateway,
                          Clock clock) {
        this.consentRepository = consentRepository;
        this.participantRepository = participantRepository;
        this.messageRepository = messageRepository;
        this.auditService = auditService;
        this.gateway = gateway;
        this.clock = clock;
    }

    public ConsentCoverage coverageFor(ChatActivation activation) {
        return coverageFor(activation.tenantId(), activation.chatId(), activation.contextRevision());
    }

    public ConsentCoverage coverageFor(UUID tenantId, UUID chatId, String contextRevision) {
        Instant now = this.clock.instant();
        List<ChatParticipant> required = this.participantRepository.findActive(tenantId, chatId).stream()
                .filter(p -> !p.bot())
                .toList();
        if (required.isEmpty()) {
            return ConsentCoverage.noParticipantsKnown();
        }

        List<ConsentRecord> records = this.consentRepository.findForChat(tenantId, chatId);
        List<ConsentGap> gaps = new ArrayList<>();
        int covered = 0;

        for (ChatParticipant participant : required) {
            List<ConsentRecord> theirs = records.stream()
                    .filter(r -> r.participantTgUserId() == participant.tgUserId())
                    .sorted(Comparator.comparing(ConsentRecord::grantedAt).reversed())
                    .toList();

            Optional<ConsentRecord> match = theirs.stream()
                    .filter(r -> r.covers(contextRevision, now))
                    .findFirst();

            if (match.isPresent()) {
                covered++;
                continue;
            }
            gaps.add(new ConsentGap(participant.tgUserId(), participant.displayName(),
                    explainGap(theirs, contextRevision, now)));
        }
        return new ConsentCoverage(required.size(), covered, List.copyOf(gaps));
    }

    private String explainGap(List<ConsentRecord> theirs, String contextRevision, Instant now) {
        if (theirs.isEmpty()) {
            return ConsentGap.MISSING;
        }
        boolean sameContext = theirs.stream()
                .anyMatch(r -> contextRevision.equals(r.contextRevision()));
        if (theirs.stream().anyMatch(ConsentRecord::revoked)
                && theirs.stream().noneMatch(r -> r.validAt(now))) {
            return ConsentGap.REVOKED;
        }
        if (theirs.stream().anyMatch(r -> r.expiredAt(now))
                && theirs.stream().noneMatch(r -> r.validAt(now))) {
            return ConsentGap.EXPIRED;
        }
        if (sameContext) {
            return ConsentGap.NARROWER_SCOPE;
        }
        return ConsentGap.MISSING;
    }

    @Transactional
    public ConsentRecord record(AuthenticatedActor actor, UUID chatId, long participantTgUserId,
                                String participantDisplayName, String purpose, String providerScope,
                                String consentVersion, String contextRevision, ConsentMethod method,
                                String evidenceRef, Instant expiresAt) {
        validateMethod(method, evidenceRef);
        ConsentRecord record = new ConsentRecord(Ids.newId(), actor.tenantId(), chatId, participantTgUserId,
                participantDisplayName, purpose, providerScope, consentVersion, contextRevision, method,
                evidenceRef, this.clock.instant(), expiresAt, null, null, actor.userId());
        this.consentRepository.insert(record);
        this.auditService.record(actor, AuditAction.CONSENT_RECORDED, chatId, AuditService.ALLOWED,
                Map.of("participantTgUserId", participantTgUserId,
                        "consentVersion", consentVersion,
                        "providerScope", providerScope,
                        "method", method.name()));
        return record;
    }

    @Transactional
    public void revokeParticipant(AuthenticatedActor actor, UUID chatId, long participantTgUserId, String reason) {
        int affected = this.consentRepository.revokeParticipant(actor.tenantId(), chatId, participantTgUserId, reason);
        // Security-audit finding F16: revoking one participant's consent used to only pause
        // the chat (which, since PAUSED.mayRetainContent()==true, keeps every message on
        // disk) -- so a participant who withdrew consent had their past messages sit there
        // for the full retention period regardless, contradicting the "revoking access
        // deletes the stored copy" promise this application makes. Purge that participant's
        // own messages here, in the same transaction as the revocation itself, the same way
        // ChatSyncService.purgeMessagesFromUnconsentedDepartures does when they leave instead.
        int deleted = this.messageRepository.deleteForSender(actor.tenantId(), chatId, participantTgUserId);
        this.auditService.record(actor, AuditAction.CONSENT_REVOKED, chatId, AuditService.ALLOWED,
                Map.of("participantTgUserId", participantTgUserId, "recordsRevoked", affected,
                        "messagesDeleted", deleted));
    }

    @Transactional
    public void revokeChat(AuthenticatedActor actor, UUID chatId, String reason) {
        int affected = this.consentRepository.revokeChat(actor.tenantId(), chatId, reason);
        this.auditService.record(actor, AuditAction.CONSENT_REVOKED, chatId, AuditService.ALLOWED,
                Map.of("scope", "chat", "recordsRevoked", affected));
    }

    public List<ConsentRecord> forChat(UUID tenantId, UUID chatId) {
        return this.consentRepository.findForChat(tenantId, chatId);
    }

    /** Whether this one participant currently has valid consent for this exact context revision. */
    public boolean hasValidConsent(UUID tenantId, UUID chatId, long participantTgUserId, String contextRevision) {
        Instant now = this.clock.instant();
        return this.consentRepository.findForChat(tenantId, chatId).stream()
                .filter(r -> r.participantTgUserId() == participantTgUserId)
                .anyMatch(r -> r.covers(contextRevision, now));
    }

    /**
     * Security-audit finding SEC-03: {@link #coverageFor} only ever asks whether the chat's
     * *currently active* participants are covered -- it says nothing about who actually sent
     * one particular message. A departed sender, a sender never captured in {@code
     * chat_participants} at all (someone who left before this application ever synced
     * membership), and an active sender whose own consent record has since expired are all
     * invisible to that check. This is the single place both message ingestion ({@code
     * ChatSyncService#store}) and message retrieval ({@code McpTools}) ask "is it acceptable
     * for this exact message's author to be represented here at all", so the two enforcement
     * points cannot quietly drift apart.
     *
     * <p>A sender with no individual identity ({@code senderTgUserId == null} -- a channel
     * post or an anonymous group admin) carries no personal data to consent over, so it is
     * treated the same way a known bot already is: eligible without a consent record of its
     * own. Forwarded messages and quoted third-party content embedded in another sender's own
     * text are a distinct, unresolved privacy concern that a sender-only check cannot solve.
     */
    public boolean authorEligible(UUID tenantId, UUID chatId, Long senderTgUserId, String contextRevision) {
        if (senderTgUserId == null) {
            return true;
        }
        boolean knownBot = this.participantRepository.find(tenantId, chatId, senderTgUserId)
                .map(ChatParticipant::bot)
                .orElse(false);
        if (knownBot) {
            return true;
        }
        return hasValidConsent(tenantId, chatId, senderTgUserId, contextRevision);
    }

    /**
     * Security-audit finding F02: an authenticated operator could previously record consent
     * "on behalf of" any participant via {@code SELF_SERVICE_LINK} with no proof that the
     * participant themselves ever saw or approved anything -- the method name promised a
     * self-service flow this application has never actually implemented (no per-participant
     * identity-verified invite/link exists). Rather than leave that gap in place, or fake a
     * verification step that doesn't really verify anything, this method is rejected outright
     * until a real identity-bound flow exists. {@code DEMO_SYNTHETIC} is rejected the moment a
     * real Telegram account is in play (any adapter other than the synthetic mock), so it can
     * never substitute for genuine consent once real people's data is involved.
     */
    private void validateMethod(ConsentMethod method, String evidenceRef) {
        if (method == ConsentMethod.SELF_SERVICE_LINK) {
            throw new IllegalArgumentException("validation.selfServiceNotAvailable");
        }
        if (method == ConsentMethod.DEMO_SYNTHETIC && this.gateway.kind() != TelegramAdapterKind.MOCK) {
            throw new IllegalArgumentException("validation.demoConsentNotAllowed");
        }
        if (method == ConsentMethod.SIGNED_ATTESTATION && (evidenceRef == null || evidenceRef.isBlank())) {
            throw new IllegalArgumentException("validation.evidenceRequired");
        }
    }
}
