package io.tgmcp.access;

/** Carries only a coarse code and an operator-safe message; never leaks internal detail. */
public class ChatAccessException extends RuntimeException {

    private final AccessErrorCode code;

    public ChatAccessException(AccessErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public AccessErrorCode code() {
        return this.code;
    }

    public static ChatAccessException denied() {
        return new ChatAccessException(AccessErrorCode.ACCESS_DENIED,
                "No accessible chat matches that identifier.");
    }

    public static ChatAccessException consentRequired(String detail) {
        return new ChatAccessException(AccessErrorCode.CONSENT_REQUIRED, detail);
    }

    public static ChatAccessException syncPending(String detail) {
        return new ChatAccessException(AccessErrorCode.SYNC_PENDING, detail);
    }

    public static ChatAccessException invalidArgument(String detail) {
        return new ChatAccessException(AccessErrorCode.INVALID_ARGUMENT, detail);
    }
}
