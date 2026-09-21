package io.tgmcp.telegram;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.tgmcp.telegram.gateway.LoginStage;
import io.tgmcp.telegram.gateway.TelegramAdapterKind;
import io.tgmcp.common.Jdbc;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TelegramConnectionRepository {

    private static final String COLUMNS = """
            id, tenant_id, owner_user_id, adapter, status, tg_user_id, tg_username, tg_phone_number,
            session_key_id, last_error, connected_at, updated_at
            """;

    private final JdbcClient jdbc;

    public TelegramConnectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The tenant's one shared Telegram connection, regardless of which of its users' logins
     * created it -- every chat, activation, and consent record is scoped by tenant alone, not
     * by which specific user signed the connection in, so callers that only know the tenant
     * (MCP access checks in particular) need to reach it without also knowing the owner.
     */
    public Optional<TelegramConnection> findByTenant(UUID tenantId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM telegram_connections WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId)
                .query(this::map)
                .optional();
    }

    public Optional<TelegramConnection> findByOwner(UUID tenantId, UUID ownerUserId) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM telegram_connections WHERE tenant_id = :tenantId AND owner_user_id = :ownerUserId")
                .param("tenantId", tenantId)
                .param("ownerUserId", ownerUserId)
                .query(this::map)
                .optional();
    }

    public Optional<TelegramConnection> find(UUID tenantId, UUID connectionId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM telegram_connections WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", connectionId)
                .query(this::map)
                .optional();
    }

    public List<TelegramConnection> findAllReady() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM telegram_connections WHERE status = 'READY'")
                .query(this::map)
                .list();
    }

    public void insert(TelegramConnection connection) {
        jdbc.sql("""
                        INSERT INTO telegram_connections
                            (id, tenant_id, owner_user_id, adapter, status, session_key_id, updated_at)
                        VALUES (:id, :tenantId, :ownerUserId, :adapter, :status, :sessionKeyId, now())
                        """)
                .param("id", connection.id())
                .param("tenantId", connection.tenantId())
                .param("ownerUserId", connection.ownerUserId())
                .param("adapter", connection.adapter().name())
                .param("status", connection.status().name())
                .param("sessionKeyId", connection.sessionKeyId())
                .update();
    }

    public void updateStatus(UUID tenantId, UUID connectionId, LoginStage status,
                             Long tgUserId, String tgUsername, String tgPhoneNumber, String lastError) {
        jdbc.sql("""
                        UPDATE telegram_connections
                           SET status = :status,
                               tg_user_id = COALESCE(:tgUserId, tg_user_id),
                               tg_username = COALESCE(:tgUsername, tg_username),
                               tg_phone_number = COALESCE(:tgPhoneNumber, tg_phone_number),
                               last_error = :lastError,
                               connected_at = CASE WHEN :status = 'READY' AND connected_at IS NULL
                                                   THEN now() ELSE connected_at END,
                               updated_at = now()
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId)
                .param("id", connectionId)
                .param("status", status.name())
                .param("tgUserId", tgUserId)
                .param("tgUsername", tgUsername)
                .param("tgPhoneNumber", tgPhoneNumber)
                .param("lastError", lastError)
                .update();
    }

    /** Stores the AES-GCM envelope produced by {@code SessionCipher}. Never the plaintext. */
    public void storeSession(UUID tenantId, UUID connectionId, byte[] ciphertext, String keyId) {
        jdbc.sql("""
                        UPDATE telegram_connections
                           SET session_ciphertext = :ciphertext, session_key_id = :keyId, updated_at = now()
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId)
                .param("id", connectionId)
                .param("ciphertext", ciphertext)
                .param("keyId", keyId)
                .update();
    }

    public Optional<byte[]> loadSession(UUID tenantId, UUID connectionId) {
        return jdbc.sql("SELECT session_ciphertext FROM telegram_connections WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", connectionId)
                .query((rs, rowNum) -> rs.getBytes("session_ciphertext"))
                .optional()
                .filter(bytes -> bytes != null && bytes.length > 0);
    }

    public void clearSession(UUID tenantId, UUID connectionId) {
        jdbc.sql("""
                        UPDATE telegram_connections
                           SET session_ciphertext = NULL, status = 'DISCONNECTED', updated_at = now()
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId)
                .param("id", connectionId)
                .update();
    }

    private TelegramConnection map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new TelegramConnection(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("owner_user_id", UUID.class),
                TelegramAdapterKind.valueOf(rs.getString("adapter")),
                LoginStage.valueOf(rs.getString("status")),
                rs.getObject("tg_user_id", Long.class),
                rs.getString("tg_username"),
                rs.getString("tg_phone_number"),
                rs.getString("session_key_id"),
                rs.getString("last_error"),
                Jdbc.instant(rs, "connected_at"),
                Jdbc.instant(rs, "updated_at"));
    }
}
