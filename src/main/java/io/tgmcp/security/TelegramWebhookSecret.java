package io.tgmcp.security;

import java.security.SecureRandom;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

/**
 * A per-process secret, generated fresh at startup and never persisted: registered with
 * Telegram as the webhook's {@code secret_token} ({@code TelegramWebhookRegistrar}) and checked
 * against every inbound {@code X-Telegram-Bot-Api-Secret-Token} header ({@code
 * TelegramBotWebhookController}), so a request that did not come from Telegram's own servers
 * (which echo back exactly the token last registered) is rejected before its body is even
 * parsed. Regenerating this on every restart is fine -- {@code TelegramWebhookRegistrar}
 * re-registers the webhook with the new value on every startup too.
 */
@Component
public class TelegramWebhookSecret {

    private final String value;

    public TelegramWebhookSecret() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        this.value = HexFormat.of().formatHex(bytes);
    }

    public String value() {
        return this.value;
    }
}
