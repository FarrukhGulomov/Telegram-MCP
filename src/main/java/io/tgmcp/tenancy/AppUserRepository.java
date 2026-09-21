package io.tgmcp.tenancy;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AppUserRepository {

    private static final String COLUMNS =
            "id, tenant_id, phone_number, display_name, role, enabled, telegram_user_id, telegram_username";

    private final JdbcClient jdbc;

    public AppUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The only way an account is ever looked up before it has proven anything: by the phone
     * number it last completed a real Telegram login with. */
    public Optional<AppUser> findByPhoneNumber(String phoneNumber) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_users WHERE phone_number = :phoneNumber")
                .param("phoneNumber", phoneNumber)
                .query(AppUser.class)
                .optional();
    }

    public Optional<AppUser> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_users WHERE id = :id")
                .param("id", id)
                .query(AppUser.class)
                .optional();
    }

    /** Resolves a Telegram identity TDLib itself just confirmed back to its account. */
    public Optional<AppUser> findByTelegramUserId(long telegramUserId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_users WHERE telegram_user_id = :telegramUserId")
                .param("telegramUserId", telegramUserId)
                .query(AppUser.class)
                .optional();
    }

    public void insert(AppUser user) {
        jdbc.sql("""
                        INSERT INTO app_users (id, tenant_id, phone_number, display_name, role, enabled,
                                               telegram_user_id, telegram_username)
                        VALUES (:id, :tenantId, :phoneNumber, :displayName, :role, :enabled,
                                :telegramUserId, :telegramUsername)
                        """)
                .param("id", user.id())
                .param("tenantId", user.tenantId())
                .param("phoneNumber", user.phoneNumber())
                .param("displayName", user.displayName())
                .param("role", user.role())
                .param("enabled", user.enabled())
                .param("telegramUserId", user.telegramUserId())
                .param("telegramUsername", user.telegramUsername())
                .update();
    }

    /**
     * Overwrites the account's identity with whatever Telegram's own login just confirmed --
     * the phone number typed to look up the account is never trusted on its own; only what a
     * completed TDLib login reports back is. Throws a unique-constraint violation if that real
     * identity already belongs to a different account (see {@code PreAuthLoginService}, which
     * treats that as "this Telegram account is already registered" and rolls back).
     */
    public void updateTelegramIdentity(UUID tenantId, UUID userId, String phoneNumber, Long telegramUserId,
                                       String telegramUsername) {
        jdbc.sql("""
                        UPDATE app_users
                        SET phone_number = :phoneNumber, telegram_user_id = :telegramUserId,
                            telegram_username = :telegramUsername
                        WHERE tenant_id = :tenantId AND id = :userId
                        """)
                .param("phoneNumber", phoneNumber)
                .param("telegramUserId", telegramUserId)
                .param("telegramUsername", telegramUsername)
                .param("tenantId", tenantId)
                .param("userId", userId)
                .update();
    }
}
