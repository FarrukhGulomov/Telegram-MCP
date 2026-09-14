package io.tgmcp.message;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.tgmcp.common.Jdbc;

/**
 * All reads and writes are tenant-scoped and use bind parameters exclusively; no fragment
 * of a query is ever built from caller-supplied text.
 */
@Repository
public class MessageRepository {

    private static final String COLUMNS = """
            id, tenant_id, chat_id, tg_message_id, sender_tg_user_id, sender_display_name,
            sent_at, edited_at, deleted_at, content, reply_to_tg_message_id, source_link
            """;

    private final JdbcClient jdbc;

    public MessageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Idempotent ingestion. Re-ingesting the same message is a no-op; an edited message
     * updates its content in place. A message already tombstoned by a deletion is not
     * resurrected.
     */
    public void upsert(StoredMessage message) {
        this.jdbc.sql("""
                        INSERT INTO messages
                            (id, tenant_id, chat_id, tg_message_id, sender_tg_user_id, sender_display_name,
                             sent_at, edited_at, content, reply_to_tg_message_id, source_link, updated_at)
                        VALUES
                            (:id, :tenantId, :chatId, :tgMessageId, :senderTgUserId, :senderDisplayName,
                             :sentAt, :editedAt, :content, :replyTo, :sourceLink, now())
                        ON CONFLICT (tenant_id, chat_id, tg_message_id) DO UPDATE SET
                             sender_display_name = EXCLUDED.sender_display_name,
                             edited_at = EXCLUDED.edited_at,
                             content = CASE WHEN messages.deleted_at IS NULL THEN EXCLUDED.content ELSE '' END,
                             reply_to_tg_message_id = EXCLUDED.reply_to_tg_message_id,
                             source_link = EXCLUDED.source_link,
                             updated_at = now()
                        """)
                .param("id", message.id())
                .param("tenantId", message.tenantId())
                .param("chatId", message.chatId())
                .param("tgMessageId", message.tgMessageId())
                .param("senderTgUserId", message.senderTgUserId())
                .param("senderDisplayName", message.senderDisplayName())
                .param("sentAt", Timestamp.from(message.sentAt()))
                .param("editedAt", message.editedAt() == null ? null : Timestamp.from(message.editedAt()))
                .param("content", message.content())
                .param("replyTo", message.replyToTgMessageId())
                .param("sourceLink", message.sourceLink())
                .update();
    }

    /**
     * Tombstones a message deleted upstream: the text is dropped immediately so that this
     * service never keeps content past its Telegram lifecycle, while the row remains so
     * that re-ingestion stays idempotent.
     */
    public int markDeleted(UUID tenantId, UUID chatId, long tgMessageId, Instant deletedAt) {
        return this.jdbc.sql("""
                        UPDATE messages
                           SET deleted_at = COALESCE(deleted_at, :deletedAt), content = '', updated_at = now()
                         WHERE tenant_id = :tenantId AND chat_id = :chatId AND tg_message_id = :tgMessageId
                        """)
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .param("tgMessageId", tgMessageId)
                .param("deletedAt", Timestamp.from(deletedAt))
                .update();
    }

    public Optional<StoredMessage> find(UUID tenantId, UUID chatId, long tgMessageId) {
        return this.jdbc.sql("SELECT " + COLUMNS + """
                         FROM messages
                        WHERE tenant_id = :tenantId AND chat_id = :chatId AND tg_message_id = :tgMessageId
                          AND deleted_at IS NULL
                        """)
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .param("tgMessageId", tgMessageId)
                .query(MessageRepository::map)
                .optional();
    }

    /**
     * Full-text search across an explicit set of chats and an explicit time range.
     * Ordered newest first with a deterministic tiebreak so that keyset paging is stable.
     */
    public MessageSlice search(UUID tenantId, Collection<UUID> chatIds, String query,
                               Instant from, Instant to, MessagePageKey after, int limit) {
        if (chatIds.isEmpty()) {
            return MessageSlice.empty();
        }
        String sql = "SELECT " + COLUMNS + """
                 FROM messages
                WHERE tenant_id = :tenantId
                  AND chat_id IN (:chatIds)
                  AND deleted_at IS NULL
                  AND sent_at >= :from AND sent_at <= :to
                  AND content_tsv @@ websearch_to_tsquery('english', :query)
                """
                + (after == null ? "" : """
                  AND (sent_at, chat_id, tg_message_id) < (:afterSentAt, :afterChatId, :afterMessageId)
                """)
                + """
                ORDER BY sent_at DESC, chat_id DESC, tg_message_id DESC
                LIMIT :limit
                """;

        JdbcClient.StatementSpec spec = this.jdbc.sql(sql)
                .param("tenantId", tenantId)
                .param("chatIds", new ArrayList<>(chatIds))
                .param("query", query)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .param("limit", limit + 1);
        if (after != null) {
            spec = spec.param("afterSentAt", Timestamp.from(after.sentAt()))
                    .param("afterChatId", after.chatId())
                    .param("afterMessageId", after.tgMessageId());
        }
        return slice(spec.query(MessageRepository::map).list(), limit);
    }

    /** Bounded chronological history for a single chat, oldest first. */
    public MessageSlice history(UUID tenantId, UUID chatId, Instant from, Instant to,
                                MessagePageKey after, int limit) {
        String sql = "SELECT " + COLUMNS + """
                 FROM messages
                WHERE tenant_id = :tenantId AND chat_id = :chatId AND deleted_at IS NULL
                  AND sent_at >= :from AND sent_at <= :to
                """
                + (after == null ? "" : """
                  AND (sent_at, tg_message_id) > (:afterSentAt, :afterMessageId)
                """)
                + """
                ORDER BY sent_at ASC, tg_message_id ASC
                LIMIT :limit
                """;

        JdbcClient.StatementSpec spec = this.jdbc.sql(sql)
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .param("limit", limit + 1);
        if (after != null) {
            spec = spec.param("afterSentAt", Timestamp.from(after.sentAt()))
                    .param("afterMessageId", after.tgMessageId());
        }
        return slice(spec.query(MessageRepository::map).list(), limit);
    }

    /** Messages immediately before a given message, oldest first. */
    public List<StoredMessage> before(UUID tenantId, UUID chatId, Instant pivotSentAt, long pivotMessageId,
                                      Instant floor, int limit) {
        List<StoredMessage> rows = this.jdbc.sql("SELECT " + COLUMNS + """
                         FROM messages
                        WHERE tenant_id = :tenantId AND chat_id = :chatId AND deleted_at IS NULL
                          AND sent_at >= :floor
                          AND (sent_at, tg_message_id) < (:pivotSentAt, :pivotMessageId)
                        ORDER BY sent_at DESC, tg_message_id DESC
                        LIMIT :limit
                        """)
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .param("floor", Timestamp.from(floor))
                .param("pivotSentAt", Timestamp.from(pivotSentAt))
                .param("pivotMessageId", pivotMessageId)
                .param("limit", limit)
                .query(MessageRepository::map)
                .list();
        List<StoredMessage> ascending = new ArrayList<>(rows);
        java.util.Collections.reverse(ascending);
        return List.copyOf(ascending);
    }

    /** Messages immediately after a given message, oldest first. */
    public List<StoredMessage> after(UUID tenantId, UUID chatId, Instant pivotSentAt, long pivotMessageId,
                                     Instant ceiling, int limit) {
        return this.jdbc.sql("SELECT " + COLUMNS + """
                         FROM messages
                        WHERE tenant_id = :tenantId AND chat_id = :chatId AND deleted_at IS NULL
                          AND sent_at <= :ceiling
                          AND (sent_at, tg_message_id) > (:pivotSentAt, :pivotMessageId)
                        ORDER BY sent_at ASC, tg_message_id ASC
                        LIMIT :limit
                        """)
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .param("ceiling", Timestamp.from(ceiling))
                .param("pivotSentAt", Timestamp.from(pivotSentAt))
                .param("pivotMessageId", pivotMessageId)
                .param("limit", limit)
                .query(MessageRepository::map)
                .list();
    }

    public ChatCoverage coverage(UUID tenantId, UUID chatId) {
        return this.jdbc.sql("""
                        SELECT min(sent_at) AS earliest, max(sent_at) AS latest, count(*) AS total
                          FROM messages
                         WHERE tenant_id = :tenantId AND chat_id = :chatId AND deleted_at IS NULL
                        """)
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .query((rs, rowNum) -> new ChatCoverage(
                        Jdbc.instant(rs, "earliest"),
                        Jdbc.instant(rs, "latest"),
                        rs.getLong("total")))
                .optional()
                .orElseGet(ChatCoverage::none);
    }

    public Optional<Long> newestMessageId(UUID tenantId, UUID chatId) {
        return this.jdbc.sql("""
                        SELECT max(tg_message_id) AS newest FROM messages
                         WHERE tenant_id = :tenantId AND chat_id = :chatId
                        """)
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .query((rs, rowNum) -> rs.getObject("newest", Long.class))
                .optional()
                .filter(java.util.Objects::nonNull);
    }

    /** Retention: physically removes rows outside the allowed window. */
    public int deleteOutsideWindow(UUID tenantId, UUID chatId, Instant keepFrom, Instant keepTo) {
        return this.jdbc.sql("""
                        DELETE FROM messages
                         WHERE tenant_id = :tenantId AND chat_id = :chatId
                           AND (sent_at < :keepFrom OR sent_at > :keepTo)
                        """)
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .param("keepFrom", Timestamp.from(keepFrom))
                .param("keepTo", Timestamp.from(keepTo))
                .update();
    }

    /** Idempotent purge used after revocation and on explicit delete-my-data requests. */
    public int deleteForChat(UUID tenantId, UUID chatId) {
        return this.jdbc.sql("DELETE FROM messages WHERE tenant_id = :tenantId AND chat_id = :chatId")
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .update();
    }

    /**
     * Purges one participant's own messages from one chat. Used when someone leaves without
     * ever having valid consent on file for the chat's current processing context (security
     * audit finding F05): message storage itself is never gated on a per-sender consent check,
     * so a message sent while that person was still a member -- but before anyone was ever
     * asked to consent, or under a context revision that has since changed -- could otherwise
     * keep circulating after they are gone and can no longer be asked at all.
     */
    public int deleteForSender(UUID tenantId, UUID chatId, long senderTgUserId) {
        return this.jdbc.sql("""
                        DELETE FROM messages
                         WHERE tenant_id = :tenantId AND chat_id = :chatId AND sender_tg_user_id = :senderTgUserId
                        """)
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .param("senderTgUserId", senderTgUserId)
                .update();
    }

    public long countForChat(UUID tenantId, UUID chatId) {
        return this.jdbc.sql("""
                        SELECT count(*) FROM messages
                         WHERE tenant_id = :tenantId AND chat_id = :chatId AND deleted_at IS NULL
                        """)
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .query(Long.class)
                .single();
    }

    private static MessageSlice slice(List<StoredMessage> rows, int limit) {
        boolean hasMore = rows.size() > limit;
        List<StoredMessage> page = hasMore ? rows.subList(0, limit) : rows;
        MessagePageKey nextKey = null;
        if (hasMore && !page.isEmpty()) {
            StoredMessage last = page.get(page.size() - 1);
            nextKey = new MessagePageKey(last.sentAt(), last.chatId(), last.tgMessageId());
        }
        return new MessageSlice(List.copyOf(page), hasMore, nextKey);
    }

    private static StoredMessage map(ResultSet rs, int rowNum) throws SQLException {
        return new StoredMessage(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("chat_id", UUID.class),
                rs.getLong("tg_message_id"),
                rs.getObject("sender_tg_user_id", Long.class),
                rs.getString("sender_display_name"),
                Jdbc.instant(rs, "sent_at"),
                Jdbc.instant(rs, "edited_at"),
                Jdbc.instant(rs, "deleted_at"),
                rs.getString("content"),
                rs.getObject("reply_to_tg_message_id", Long.class),
                rs.getString("source_link"));
    }
}
