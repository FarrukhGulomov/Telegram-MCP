package io.tgmcp.telegram.gateway;

public abstract class TelegramGatewayException extends RuntimeException {

    protected TelegramGatewayException(String message) {
        super(message);
    }

    protected TelegramGatewayException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Whether a retry could plausibly succeed without operator intervention. */
    public abstract boolean retryable();

    public abstract String code();
}
