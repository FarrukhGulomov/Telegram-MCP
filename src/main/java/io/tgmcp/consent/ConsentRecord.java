package io.tgmcp.consent;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * One participant's consent, scoped to a chat, a purpose, a provider scope and a consent
 * version. Consent is explicit, revocable, and limited to that context: change any part of
 * the context and this record stops covering it.
 */
public record ConsentRecord(UUID id,
                            UUID tenantId,
                            UUID chatId,
                            long participantTgUserId,
                            String participantDisplayName,
                            String purpose,
                            String providerScope,
                            String consentVersion,
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

    /** Whether this record covers exactly the context being asked about. */
    public boolean covers(String requiredPurpose, String requiredVersion, Set<String> requiredProviders, Instant now) {
        return validAt(now)
                && this.purpose.equals(requiredPurpose)
                && this.consentVersion.equals(requiredVersion)
                && providers().containsAll(requiredProviders);
    }
}
