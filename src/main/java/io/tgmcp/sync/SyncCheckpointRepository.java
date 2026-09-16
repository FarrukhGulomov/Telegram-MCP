package io.tgmcp.sync;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.tgmcp.common.Ids;
import io.tgmcp.common.Jdbc;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SyncCheckpointRepository {

    private static final String COLUMNS = """
            id, tenant_id, chat_id, newest_synced_message_id, oldest_synced_message_id,
            synced_from, synced_to, backfill_complete, last_attempt_at, last_success_at,
            consecutive_failures, next_attempt_at, last_error_code, last_error_message, halted
            """;

    private final JdbcClient jdbc;

    public SyncCheckpointRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SyncCheckpoint> find(UUID tenantId, UUID chatId) {
        return this.jdbc.sql("SELECT " + COLUMNS
                        + " FROM sync_checkpoints WHERE tenant_id = :tenantId AND chat_id = :chatId")
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .query(SyncCheckpointRepository::map)
                .optional();
    }

    public SyncCheckpoint findOrCreate(UUID tenantId, UUID chatId) {
        return find(tenantId, chatId).orElseGet(() -> {
            this.jdbc.sql("""
                            INSERT INTO sync_checkpoints (id, tenant_id, chat_id)
                            VALUES (:id, :tenantId, :chatId)
                            ON CONFLICT (chat_id) DO NOTHING
                            """)
                    .param("id", Ids.newId())
                    .param("tenantId", tenantId)
                    .param("chatId", chatId)
                    .update();
            return find(tenantId, chatId).orElseThrow();
        });
    }

    public List<SyncCheckpoint> findAll() {
        return this.jdbc.sql("SELECT " + COLUMNS + " FROM sync_checkpoints")
                .query(SyncCheckpointRepository::map)
                .list();
    }

    public void recordSuccess(UUID tenantId, UUID chatId, Long newestId, Long oldestId,
                              Instant syncedFrom, Instant syncedTo, boolean backfillComplete, Instant now) {
        this.jdbc.sql("""
                        UPDATE sync_checkpoints SET
                            newest_synced_message_id = GREATEST(COALESCE(newest_synced_message_id, :newestId), :newestId),
                            oldest_synced_message_id = LEAST(COALESCE(oldest_synced_message_id, :oldestId), :oldestId),
                            synced_from = LEAST(COALESCE(synced_from, :syncedFrom), :syncedFrom),
                            synced_to = GREATEST(COALESCE(synced_to, :syncedTo), :syncedTo),
                            backfill_complete = :backfillComplete,
                            last_attempt_at = :now,
                            last_success_at = :now,
                            consecutive_failures = 0,
                            next_attempt_at = NULL,
                            last_error_code = NULL,
                            last_error_message = NULL,
                            halted = false,
                            updated_at = now()
                         WHERE tenant_id = :tenantId AND chat_id = :chatId
                        """)
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .param("newestId", newestId)
                .param("oldestId", oldestId)
                .param("syncedFrom", syncedFrom == null ? null : Timestamp.from(syncedFrom))
                .param("syncedTo", syncedTo == null ? null : Timestamp.from(syncedTo))
                .param("backfillComplete", backfillComplete)
                .param("now", Timestamp.from(now))
                .update();
    }

    public void recordAttempt(UUID tenantId, UUID chatId, Instant now) {
        this.jdbc.sql("""
                        UPDATE sync_checkpoints SET last_attempt_at = :now, updated_at = now()
                         WHERE tenant_id = :tenantId AND chat_id = :chatId
                        """)
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .param("now", Timestamp.from(now))
                .update();
    }

    public void recordFailure(UUID tenantId, UUID chatId, String errorCode, String errorMessage,
                              Instant nextAttemptAt, boolean halted, Instant now) {
        this.jdbc.sql("""
                        UPDATE sync_checkpoints SET
                            last_attempt_at = :now,
                            consecutive_failures = consecutive_failures + 1,
                            next_attempt_at = :nextAttemptAt,
                            last_error_code = :errorCode,
                            last_error_message = :errorMessage,
                            halted = :halted,
                            updated_at = now()
                         WHERE tenant_id = :tenantId AND chat_id = :chatId
                        """)
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .param("errorCode", errorCode)
                .param("errorMessage", errorMessage)
                .param("nextAttemptAt", nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt))
                .param("halted", halted)
                .param("now", Timestamp.from(now))
                .update();
    }

    /**
     * Clears a halted checkpoint's failure state without touching what has already been
     * ingested (unlike {@link #reset}, which also wipes synced position and backfill progress
     * for a full re-ingest). An operator explicitly asking to sync is exactly the human
     * attention a halt is meant to wait for, so a manual sync clears it first rather than
     * silently doing nothing because {@link SyncCheckpoint#dueAt} still says no.
     */
    public void clearHalt(UUID tenantId, UUID chatId) {
        this.jdbc.sql("""
                        UPDATE sync_checkpoints SET
                            consecutive_failures = 0, next_attempt_at = NULL,
                            last_error_code = NULL, last_error_message = NULL, halted = false,
                            updated_at = now()
                         WHERE tenant_id = :tenantId AND chat_id = :chatId
                        """)
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .update();
    }

    public void reset(UUID tenantId, UUID chatId) {
        this.jdbc.sql("""
                        UPDATE sync_checkpoints SET
                            newest_synced_message_id = NULL, oldest_synced_message_id = NULL,
                            synced_from = NULL, synced_to = NULL, backfill_complete = false,
                            last_success_at = NULL, consecutive_failures = 0, next_attempt_at = NULL,
                            last_error_code = NULL, last_error_message = NULL, halted = false, updated_at = now()
                         WHERE tenant_id = :tenantId AND chat_id = :chatId
                        """)
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .update();
    }

    private static SyncCheckpoint map(ResultSet rs, int rowNum) throws SQLException {
        return new SyncCheckpoint(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("chat_id", UUID.class),
                rs.getObject("newest_synced_message_id", Long.class),
                rs.getObject("oldest_synced_message_id", Long.class),
                Jdbc.instant(rs, "synced_from"),
                Jdbc.instant(rs, "synced_to"),
                rs.getBoolean("backfill_complete"),
                Jdbc.instant(rs, "last_attempt_at"),
                Jdbc.instant(rs, "last_success_at"),
                rs.getInt("consecutive_failures"),
                Jdbc.instant(rs, "next_attempt_at"),
                rs.getString("last_error_code"),
                rs.getString("last_error_message"),
                rs.getBoolean("halted"));
    }
}
