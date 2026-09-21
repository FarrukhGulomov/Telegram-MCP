package io.tgmcp.tenancy;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AppUserRepository {

    private static final String COLUMNS =
            "id, tenant_id, email, password_hash, display_name, role, enabled, telegram_user_id, "
                    + "telegram_username";

    private final JdbcClient jdbc;

    public AppUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppUser> findByEmail(String email) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_users WHERE lower(email) = lower(:email)")
                .param("email", email)
                .query(AppUser.class)
                .optional();
    }

    public Optional<AppUser> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_users WHERE id = :id")
                .param("id", id)
                .query(AppUser.class)
                .optional();
    }

    /** Used by "Sign in with Telegram" to resolve a verified Telegram identity back to its account. */
    public Optional<AppUser> findByTelegramUserId(long telegramUserId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_users WHERE telegram_user_id = :telegramUserId")
                .param("telegramUserId", telegramUserId)
                .query(AppUser.class)
                .optional();
    }

    /** Used by the "sign in with a Telegram code" entry point when a username, not an email, is entered. */
    public Optional<AppUser> findByTelegramUsername(String telegramUsername) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_users WHERE lower(telegram_username) = lower(:username)")
                .param("username", telegramUsername)
                .query(AppUser.class)
                .optional();
    }

    public void updatePasswordHash(UUID tenantId, UUID userId, String newPasswordHash) {
        jdbc.sql("UPDATE app_users SET password_hash = :hash WHERE tenant_id = :tenantId AND id = :id")
                .param("hash", newPasswordHash)
                .param("tenantId", tenantId)
                .param("id", userId)
                .update();
    }

    public void insert(AppUser user) {
        jdbc.sql("""
                        INSERT INTO app_users (id, tenant_id, email, password_hash, display_name, role, enabled,
                                               telegram_user_id, telegram_username)
                        VALUES (:id, :tenantId, :email, :passwordHash, :displayName, :role, :enabled,
                                :telegramUserId, :telegramUsername)
                        """)
                .param("id", user.id())
                .param("tenantId", user.tenantId())
                .param("email", user.email())
                .param("passwordHash", user.passwordHash())
                .param("displayName", user.displayName())
                .param("role", user.role())
                .param("enabled", user.enabled())
                .param("telegramUserId", user.telegramUserId())
                .param("telegramUsername", user.telegramUsername())
                .update();
    }
}
