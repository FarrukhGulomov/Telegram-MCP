package io.tgmcp.access;

/**
 * Deliberately coarse, caller-safe error codes.
 *
 * <p>{@link #ACCESS_DENIED} is returned for a chat that does not exist, belongs to another
 * tenant, was never selected, or was revoked — so a caller cannot use the error to probe
 * for the existence of a chat it is not allowed to see.
 */
public enum AccessErrorCode {
    ACCESS_DENIED,
    CONSENT_REQUIRED,
    SYNC_PENDING,
    INVALID_ARGUMENT,
    RATE_LIMITED,
    INTERNAL_ERROR,
    /**
     * Security-audit finding SEC-R03: the Telegram connection backing this tenant is not
     * currently READY (mid re-login, or torn down and not yet reconnected). Distinct from
     * {@link #SYNC_PENDING} (which only ever means "no successful sync yet") and from the
     * separate, time-based membership-freshness check -- this fires immediately the moment the
     * live connection is known to be unavailable, rather than only after staleness accumulates
     * for a full freshness-budget window.
     */
    CONNECTION_UNAVAILABLE
}
