package io.tgmcp.telegram.gateway;

/** Network blip, timeout or server-side error. Safe to retry with bounded backoff. */
public class TelegramTransientException extends TelegramGatewayException {

    public TelegramTransientException(String message) {
        super(message);
    }

    public TelegramTransientException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public boolean retryable() {
        return true;
    }

    @Override
    public String code() {
        return "TRANSIENT";
    }
}
