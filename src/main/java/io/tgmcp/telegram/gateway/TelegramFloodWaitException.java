package io.tgmcp.telegram.gateway;

import java.time.Duration;

/** Telegram asked us to back off for a specific amount of time (FLOOD_WAIT_X). */
public class TelegramFloodWaitException extends TelegramGatewayException {

    private final Duration retryAfter;

    public TelegramFloodWaitException(Duration retryAfter) {
        super("Telegram flood wait for " + retryAfter.toSeconds() + "s");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return this.retryAfter;
    }

    @Override
    public boolean retryable() {
        return true;
    }

    @Override
    public String code() {
        return "FLOOD_WAIT";
    }
}
