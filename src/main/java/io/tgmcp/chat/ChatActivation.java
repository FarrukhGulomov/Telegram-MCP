package io.tgmcp.chat;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Authorization to *process* one chat's content — deliberately separate from the
 * authorization to connect a Telegram account.
 */
public record ChatActivation(UUID id,
                             UUID tenantId,
                             UUID chatId,
                             ActivationState state,
                             String purpose,
                             String providerScope,
                             String consentVersion,
                             Instant approvedFrom,
                             Instant approvedTo,
                             int retentionDays,
                             String membershipFingerprint,
                             String pauseReason,
                             Instant activatedAt,
                             UUID activatedByUserId) {

    public Set<String> providers() {
        return new LinkedHashSet<>(Arrays.stream(providerScope.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList());
    }

    public boolean coversProvider(String provider) {
        return providers().contains(provider);
    }

    public boolean withinApprovedRange(Instant at) {
        return !at.isBefore(approvedFrom) && !at.isAfter(approvedTo);
    }

    /** The earliest message timestamp still allowed by both the approved range and retention. */
    public Instant effectiveFrom(Instant now) {
        Instant retentionFloor = now.minus(java.time.Duration.ofDays(retentionDays));
        return approvedFrom.isAfter(retentionFloor) ? approvedFrom : retentionFloor;
    }
}
