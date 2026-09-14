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
    INTERNAL_ERROR
}
