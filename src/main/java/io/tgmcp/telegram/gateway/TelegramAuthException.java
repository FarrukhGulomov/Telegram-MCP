package io.tgmcp.telegram.gateway;

/**
 * The Telegram session is no longer valid (signed out, revoked from another device,
 * session key rejected). Retrying cannot fix this, so sync halts and asks a human.
 */
public class TelegramAuthException extends TelegramGatewayException {

    public TelegramAuthException(String message) {
        super(message);
    }

    @Override
    public boolean retryable() {
        return false;
    }

    @Override
    public String code() {
        return "AUTH_FAILED";
    }
}
