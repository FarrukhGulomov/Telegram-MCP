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
            id, tenant_id, owner_user_id, adapter, status, tg_user_id, tg_username,
            session_key_id, last_error, connected_at, updated_at
            """;

    private final JdbcClient jdbc;

    public TelegramConnectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
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

    /**
     * Security-audit finding SEC-01: {@code existsActiveForOtherOwner} below is a plain
     * {@code SELECT}, not an atomic lease acquisition -- two concurrent {@code beginLogin}
     * transactions could both read "no other active owner" before either had committed, and
     * both then proceed to claim the one shared native session. {@code
     * pg_advisory_xact_lock} serializes every caller of this method against every other,
     * cluster-wide, for the lifetime of the calling transaction (released automatically on
     * commit or rollback) -- there is no data row to lock instead, since the very first login
     * ever attempted has none yet. Must be called inside the same transaction that then checks
     * {@link #existsActiveForOtherOwner} and persists the new owner, or it protects nothing.
     * The key is an arbitrary constant distinguishing this lock from any other advisory lock
     * this application might take in the future.
     */
    private static final long SINGLE_OWNER_LEASE_LOCK_KEY = 7_285_014_419_581_223_001L;

    public void acquireSingleOwnerLease() {
        // pg_advisory_xact_lock returns void; cast to text so the driver reports an ordinary
        // mappable column instead of one Spring has no RowMapper for. The value is discarded --
        // only the side effect (blocking until the lock is held) matters.
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)::text")
                .param("key", SINGLE_OWNER_LEASE_LOCK_KEY)
                .query(String.class)
                .single();
    }

    /**
     * Whether some owner other than (tenantId, ownerUserId) currently has a non-disconnected
     * session. The TDLIB adapter is one native client shared process-wide — see {@link
     * io.tgmcp.telegram.tdlib.TdlibTelegramGateway} — so two owners mid-login (or logged in)
     * at once would silently share that single Telegram identity. Used to enforce a hard
     * single-connection guard for that adapter until per-owner native session isolation
     * exists. Callers must hold {@link #acquireSingleOwnerLease()} first for this check (and
     * whatever it decides) to be atomic rather than a check-then-act race.
     */
    public boolean existsActiveForOtherOwner(UUID tenantId, UUID ownerUserId) {
        Boolean exists = jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM telegram_connections
                             WHERE status <> 'DISCONNECTED'
                               AND NOT (tenant_id = :tenantId AND owner_user_id = :ownerUserId)
                        )
                        """)
                .param("tenantId", tenantId)
                .param("ownerUserId", ownerUserId)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(exists);
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
                             Long tgUserId, String tgUsername, String lastError) {
        jdbc.sql("""
                        UPDATE telegram_connections
                           SET status = :status,
                               tg_user_id = COALESCE(:tgUserId, tg_user_id),
                               tg_username = COALESCE(:tgUsername, tg_username),
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
                rs.getString("session_key_id"),
                rs.getString("last_error"),
                Jdbc.instant(rs, "connected_at"),
                Jdbc.instant(rs, "updated_at"));
    }
}
