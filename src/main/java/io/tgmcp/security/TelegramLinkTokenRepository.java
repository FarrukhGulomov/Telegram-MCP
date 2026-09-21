package io.tgmcp.security;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TelegramLinkTokenRepository {

    private static final String COLUMNS =
            "token, telegram_user_id, telegram_username, linked_at, expires_at, created_at";

    private final JdbcClient jdbc;

    public TelegramLinkTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(TelegramLinkToken linkToken) {
        this.jdbc.sql("""
                        INSERT INTO telegram_link_tokens (token, telegram_user_id, telegram_username, linked_at,
                                                           expires_at, created_at)
                        VALUES (:token, :telegramUserId, :telegramUsername, :linkedAt, :expiresAt, :createdAt)
                        """)
                .param("token", linkToken.token())
                .param("telegramUserId", linkToken.telegramUserId())
                .param("telegramUsername", linkToken.telegramUsername())
                .param("linkedAt", linkToken.linkedAt() == null ? null : Timestamp.from(linkToken.linkedAt()))
                .param("expiresAt", Timestamp.from(linkToken.expiresAt()))
                .param("createdAt", Timestamp.from(linkToken.createdAt()))
                .update();
    }

    public Optional<TelegramLinkToken> findByToken(String token) {
        return this.jdbc.sql("SELECT " + COLUMNS + " FROM telegram_link_tokens WHERE token = :token")
                .param("token", token)
                .query(TelegramLinkToken.class)
                .optional();
    }

    /**
     * Records that {@code token} was claimed by this Telegram identity -- only while the token
     * is still pending (never overwrites an already-linked row), and only one row at a time, so
     * a webhook update replayed twice for the same token has no further effect the second time.
     *
     * @return {@code true} if this call is what linked it.
     */
    public boolean claim(String token, long telegramUserId, String telegramUsername, Instant now) {
        int updated = this.jdbc.sql("""
                        UPDATE telegram_link_tokens
                        SET telegram_user_id = :telegramUserId, telegram_username = :telegramUsername,
                            linked_at = :linkedAt
                        WHERE token = :token AND linked_at IS NULL AND expires_at > :now
                        """)
                .param("telegramUserId", telegramUserId)
                .param("telegramUsername", telegramUsername)
                .param("linkedAt", Timestamp.from(now))
                .param("token", token)
                .param("now", Timestamp.from(now))
                .update();
        return updated > 0;
    }
}
