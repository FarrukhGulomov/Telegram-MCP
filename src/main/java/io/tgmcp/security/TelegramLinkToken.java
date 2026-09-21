package io.tgmcp.security;

import java.time.Instant;

/** One "open the bot and press Start" attempt. See {@code V7__telegram_bot_linking.sql}. */
public record TelegramLinkToken(String token, Long telegramUserId, String telegramUsername, Instant linkedAt,
                                Instant expiresAt, Instant createdAt) {

    public boolean isLinked() {
        return this.linkedAt != null;
    }
}
