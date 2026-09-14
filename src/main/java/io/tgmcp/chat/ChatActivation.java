package io.tgmcp.chat;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Authorization to *process* one chat's content — deliberately separate from the
 * authorization to connect a Telegram account.
 *
 * <p>{@code contextRevision} is an opaque identity for the whole processing context (purpose,
 * provider scope, consent version, approved range and retention, taken together). It changes
 * whenever any of those change — see {@link io.tgmcp.chat.ChatActivationService} — so a consent
 * record only ever covers the exact revision it was granted under, never a context that merely
 * happens to share some of the same field values (security audit finding F03).
 */
public record ChatActivation(UUID id,
                             UUID tenantId,
                             UUID chatId,
                             ActivationState state,
                             String purpose,
                             String providerScope,
                             String consentVersion,
                             String contextRevision,
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
