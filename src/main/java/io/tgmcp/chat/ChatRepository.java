package io.tgmcp.chat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.tgmcp.common.Jdbc;

/**
 * Every query is tenant-scoped. There is deliberately no "find by id" without a tenant:
 * a caller cannot reach another tenant's chat even by guessing a primary key.
 */
@Repository
public class ChatRepository {

    private static final String COLUMNS =
            "id, tenant_id, connection_id, tg_chat_id, title, chat_type, username, member_count, discovered_at";

    private final JdbcClient jdbc;

    public ChatRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Chat> find(UUID tenantId, UUID chatId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM chats WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", chatId)
                .query(this::map)
                .optional();
    }

    public List<Chat> findByConnection(UUID tenantId, UUID connectionId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM chats
                        WHERE tenant_id = :tenantId AND connection_id = :connectionId
                        ORDER BY title
                        """)
                .param("tenantId", tenantId)
                .param("connectionId", connectionId)
                .query(this::map)
                .list();
    }

    public Optional<Chat> findByTgId(UUID tenantId, UUID connectionId, long tgChatId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM chats
                        WHERE tenant_id = :tenantId AND connection_id = :connectionId AND tg_chat_id = :tgChatId
                        """)
                .param("tenantId", tenantId)
                .param("connectionId", connectionId)
                .param("tgChatId", tgChatId)
                .query(this::map)
                .optional();
    }

    /** Idempotent: re-discovering a chat refreshes its metadata but never duplicates it. */
    public void upsert(Chat chat) {
        jdbc.sql("""
                        INSERT INTO chats (id, tenant_id, connection_id, tg_chat_id, title, chat_type, username, member_count)
                        VALUES (:id, :tenantId, :connectionId, :tgChatId, :title, :chatType, :username, :memberCount)
                        ON CONFLICT (tenant_id, connection_id, tg_chat_id)
                        DO UPDATE SET title = EXCLUDED.title,
                                      chat_type = EXCLUDED.chat_type,
                                      username = EXCLUDED.username,
                                      member_count = EXCLUDED.member_count
                        """)
                .param("id", chat.id())
                .param("tenantId", chat.tenantId())
                .param("connectionId", chat.connectionId())
                .param("tgChatId", chat.tgChatId())
                .param("title", chat.title())
                .param("chatType", chat.chatType())
                .param("username", chat.username())
                .param("memberCount", chat.memberCount())
                .update();
    }

    private Chat map(ResultSet rs, int rowNum) throws SQLException {
        return new Chat(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("connection_id", UUID.class),
                rs.getLong("tg_chat_id"),
                rs.getString("title"),
                rs.getString("chat_type"),
                rs.getString("username"),
                rs.getObject("member_count", Integer.class),
                Jdbc.instant(rs, "discovered_at"));
    }
}
