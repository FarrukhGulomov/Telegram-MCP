package io.tgmcp.security;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LoginVerificationCodeRepository {

    private static final String COLUMNS = "id, app_user_id, code_hash, expires_at, consumed_at, attempts, created_at";

    private final JdbcClient jdbc;

    public LoginVerificationCodeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(LoginVerificationCode code) {
        this.jdbc.sql("""
                        INSERT INTO login_verification_codes (id, app_user_id, code_hash, expires_at, consumed_at,
                                                               attempts, created_at)
                        VALUES (:id, :appUserId, :codeHash, :expiresAt, :consumedAt, :attempts, :createdAt)
                        """)
                .param("id", code.id())
                .param("appUserId", code.appUserId())
                .param("codeHash", code.codeHash())
                .param("expiresAt", Timestamp.from(code.expiresAt()))
                .param("consumedAt", code.consumedAt() == null ? null : Timestamp.from(code.consumedAt()))
                .param("attempts", code.attempts())
                .param("createdAt", Timestamp.from(code.createdAt()))
                .update();
    }

    /**
     * The most recently issued code for this user, whether or not it is still usable. Ordered
     * by {@code seq} (a plain insertion-order tiebreaker), not {@code created_at}: two codes for
     * the same user can share an identical timestamp under a frozen or low-resolution clock, and
     * this must always resolve to the one actually issued last.
     */
    public Optional<LoginVerificationCode> findLatestForUser(UUID appUserId) {
        return this.jdbc.sql("SELECT " + COLUMNS + " FROM login_verification_codes "
                        + "WHERE app_user_id = :appUserId ORDER BY seq DESC LIMIT 1")
                .param("appUserId", appUserId)
                .query(LoginVerificationCode.class)
                .optional();
    }

    public void incrementAttempts(UUID id) {
        this.jdbc.sql("UPDATE login_verification_codes SET attempts = attempts + 1 WHERE id = :id")
                .param("id", id)
                .update();
    }

    public void markConsumed(UUID id, Instant consumedAt) {
        this.jdbc.sql("UPDATE login_verification_codes SET consumed_at = :consumedAt WHERE id = :id")
                .param("id", id)
                .param("consumedAt", Timestamp.from(consumedAt))
                .update();
    }
}
