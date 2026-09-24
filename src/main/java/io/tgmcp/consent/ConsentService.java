package io.tgmcp.consent;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    /**
     * Per this deployment's explicit operator decision, no per-participant consent is
     * collected before a chat may be activated or read: the connected Telegram account's own
     * decision to select and activate a chat is the only authorization this deployment
     * requires, matching the operator's own account and their own legal responsibility for
     * what they choose to expose to an AI provider. Always reports full coverage rather than
     * removing the consent-collection subsystem outright (recording, revoking and displaying
     * consent through {@link #record}/{@link #revokeParticipant}/{@link #forChat} still work
     * unchanged), so nothing here blocks {@link io.tgmcp.chat.ChatActivationService#activate}
     * or gets filtered by {@link #authorEligible} for lack of a consent record.
     */
    public ConsentCoverage coverageFor(UUID tenantId, UUID chatId, String contextRevision) {
        int participants = (int) this.participantRepository.findActive(tenantId, chatId).stream()
                .filter(p -> !p.bot())
                .count();
        return new ConsentCoverage(participants, participants, List.of());
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

    /**
     * Per the same operator decision as {@link #coverageFor}: no participant's stored consent
     * record (or lack of one) gates anything here any more, so this always reports valid
     * consent. The two callers this backs -- {@link #authorEligible} (read- and write-time
     * per-author filtering) and {@code ChatSyncService#purgeMessagesFromUnconsentedAuthors}
     * (deletes a departed/lapsed participant's messages) -- both stop filtering or purging.
     */
    public boolean hasValidConsent(UUID tenantId, UUID chatId, long participantTgUserId, String contextRevision) {
        return true;
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
        // Security-audit finding H6: hasValidConsent() below is an unconditional true (the
        // operator's own 2026-09-20 decision -- see its javadoc), but that decision was about no
        // longer *requiring* consent to exist, never about ignoring an *explicit* revocation
        // once one has actually been recorded through /consent/revoke. Without this check, a
        // chat revoked for one participant and later resumed would start storing and serving
        // that same participant's new messages again -- the "Revoke" action's UI promise
        // ("ends access for good") silently stopped being true the moment consent stopped
        // gating anything else.
        if (this.consentRepository.latestConsentIsRevoked(tenantId, chatId, senderTgUserId)) {
            return false;
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
     *
     * <p>{@code OPERATOR_CONFIRMED} deliberately imposes no evidence requirement (see its
     * javadoc): unlike {@code SELF_SERVICE_LINK} it is not rejected, because it never claims
     * the participant proved anything themselves -- it is still, honestly, the operator's own
     * unverified record.
     */
    /**
     * Security-audit finding SEC-08: the shortest string that still counts as a real evidence
     * reference is inherently a judgement call, but "a" or "ok" plainly is not one -- a plain
     * non-blank check accepted either. This is a low bar, not a guarantee of genuine evidence:
     * it cannot verify that a reference actually points to anything, only rule out the most
     * obvious placeholder text and push recorders toward naming something an auditor could
     * later go and look at (a document link, a message id, a ticket number, a dated
     * description of the interaction).
     */
    private static final int MIN_EVIDENCE_REFERENCE_LENGTH = 15;

    private void validateMethod(ConsentMethod method, String evidenceRef) {
        if (method == ConsentMethod.SELF_SERVICE_LINK) {
            throw new IllegalArgumentException("validation.selfServiceNotAvailable");
        }
        if (method == ConsentMethod.DEMO_SYNTHETIC && this.gateway.kind() != TelegramAdapterKind.MOCK) {
            throw new IllegalArgumentException("validation.demoConsentNotAllowed");
        }
        if (method == ConsentMethod.SIGNED_ATTESTATION) {
            String trimmed = evidenceRef == null ? "" : evidenceRef.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("validation.evidenceRequired");
            }
            if (trimmed.length() < MIN_EVIDENCE_REFERENCE_LENGTH) {
                throw new IllegalArgumentException("validation.evidenceTooShort");
            }
        }
    }
}
