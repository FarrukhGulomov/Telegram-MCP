package io.tgmcp.telegram.gateway;

/** The connected account can no longer read this chat (kicked, chat deleted). Not retryable. */
public class TelegramPermissionException extends TelegramGatewayException {

    public TelegramPermissionException(String message) {
        super(message);
    }

    @Override
    public boolean retryable() {
        return false;
    }

    @Override
    public String code() {
        return "PERMISSION_DENIED";
    }
}
