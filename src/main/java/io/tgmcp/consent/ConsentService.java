package io.tgmcp.consent;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.tgmcp.audit.AuditAction;
import io.tgmcp.audit.AuditService;
import io.tgmcp.chat.ChatActivation;
import io.tgmcp.chat.ChatParticipant;
import io.tgmcp.chat.ChatParticipantRepository;
import io.tgmcp.common.Ids;
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
    private final AuditService auditService;
    private final Clock clock;

    public ConsentService(ConsentRepository consentRepository,
                          ChatParticipantRepository participantRepository,
                          AuditService auditService,
                          Clock clock) {
        this.consentRepository = consentRepository;
        this.participantRepository = participantRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    public ConsentCoverage coverageFor(ChatActivation activation) {
        return coverageFor(activation.tenantId(), activation.chatId(), activation.purpose(),
                activation.consentVersion(), activation.providers());
    }

    public ConsentCoverage coverageFor(UUID tenantId, UUID chatId, String purpose,
                                       String consentVersion, Set<String> providers) {
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
                    .filter(r -> r.covers(purpose, consentVersion, providers, now))
                    .findFirst();

            if (match.isPresent()) {
                covered++;
                continue;
            }
            gaps.add(new ConsentGap(participant.tgUserId(), participant.displayName(),
                    explainGap(theirs, purpose, consentVersion, providers, now)));
        }
        return new ConsentCoverage(required.size(), covered, List.copyOf(gaps));
    }

    private String explainGap(List<ConsentRecord> theirs, String purpose, String version,
                              Set<String> providers, Instant now) {
        if (theirs.isEmpty()) {
            return ConsentGap.MISSING;
        }
        boolean sameContext = theirs.stream()
                .anyMatch(r -> r.purpose().equals(purpose) && r.consentVersion().equals(version));
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
                                String consentVersion, ConsentMethod method, String evidenceRef,
                                Instant expiresAt) {
        if (method == ConsentMethod.SIGNED_ATTESTATION && (evidenceRef == null || evidenceRef.isBlank())) {
            throw new IllegalArgumentException(
                    "Recording consent on someone's behalf requires an evidence reference");
        }
        ConsentRecord record = new ConsentRecord(Ids.newId(), actor.tenantId(), chatId, participantTgUserId,
                participantDisplayName, purpose, providerScope, consentVersion, method, evidenceRef,
                this.clock.instant(), expiresAt, null, null, actor.userId());
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
        this.auditService.record(actor, AuditAction.CONSENT_REVOKED, chatId, AuditService.ALLOWED,
                Map.of("participantTgUserId", participantTgUserId, "recordsRevoked", affected));
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
}
