package io.tgmcp.consent;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * One participant's consent, scoped to a chat and an immutable processing-context revision
 * (see {@link io.tgmcp.chat.ChatActivation#contextRevision()}). Consent is explicit, revocable,
 * and limited to that exact revision: change any part of the context — purpose, provider
 * scope, consent version, approved range or retention — and this record stops covering it,
 * even if some individual fields still happen to match (security audit finding F03).
 *
 * <p>{@code purpose}/{@code providerScope}/{@code consentVersion} are kept as a human-readable
 * record of what was agreed to at the time, for audit and display; only {@code contextRevision}
 * decides coverage.
 */
public record ConsentRecord(UUID id,
                            UUID tenantId,
                            UUID chatId,
                            long participantTgUserId,
                            String participantDisplayName,
                            String purpose,
                            String providerScope,
                            String consentVersion,
                            String contextRevision,
                            ConsentMethod method,
                            String evidenceRef,
                            Instant grantedAt,
                            Instant expiresAt,
                            Instant revokedAt,
                            String revokedReason,
                            UUID recordedByUserId) {

    public Set<String> providers() {
        return new LinkedHashSet<>(Arrays.stream(providerScope.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList());
    }

    public boolean revoked() {
        return this.revokedAt != null;
    }

    public boolean expiredAt(Instant now) {
        return this.expiresAt != null && !now.isBefore(this.expiresAt);
    }

    public boolean validAt(Instant now) {
        return !revoked() && !expiredAt(now) && !now.isBefore(this.grantedAt);
    }

    /** Whether this record covers exactly the processing-context revision being asked about. */
    public boolean covers(String requiredContextRevision, Instant now) {
        return validAt(now)
                && this.contextRevision != null
                && this.contextRevision.equals(requiredContextRevision);
    }
}
