package io.tgmcp.chat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.tgmcp.common.Hashing;
import io.tgmcp.common.Jdbc;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ChatParticipantRepository {

    private static final String COLUMNS =
            "id, tenant_id, chat_id, tg_user_id, display_name, is_bot, first_seen_at, left_at";

    private final JdbcClient jdbc;

    public ChatParticipantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ChatParticipant> findActive(UUID tenantId, UUID chatId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM chat_participants
                        WHERE tenant_id = :tenantId AND chat_id = :chatId AND left_at IS NULL
                        ORDER BY display_name
                        """)
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .query(this::map)
                .list();
    }

    /** One participant regardless of whether they are currently active or have left. */
    public Optional<ChatParticipant> find(UUID tenantId, UUID chatId, long tgUserId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM chat_participants
                        WHERE tenant_id = :tenantId AND chat_id = :chatId AND tg_user_id = :tgUserId
                        """)
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .param("tgUserId", tgUserId)
                .query(this::map)
                .optional();
    }

    /**
     * Every participant ever seen in this chat, active or departed. Security-audit finding
     * SEC-03: consent can lapse (expire or be revoked) without the participant ever leaving,
     * so reconciling only against newly-detected departures is not enough to catch it.
     */
    public List<ChatParticipant> findAll(UUID tenantId, UUID chatId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM chat_participants
                        WHERE tenant_id = :tenantId AND chat_id = :chatId
                        ORDER BY display_name
                        """)
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .query(this::map)
                .list();
    }

    public void upsert(ChatParticipant participant) {
        jdbc.sql("""
                        INSERT INTO chat_participants (id, tenant_id, chat_id, tg_user_id, display_name, is_bot)
                        VALUES (:id, :tenantId, :chatId, :tgUserId, :displayName, :bot)
                        ON CONFLICT (tenant_id, chat_id, tg_user_id)
                        DO UPDATE SET display_name = EXCLUDED.display_name,
                                      is_bot = EXCLUDED.is_bot,
                                      left_at = NULL
                        """)
                .param("id", participant.id())
                .param("tenantId", participant.tenantId())
                .param("chatId", participant.chatId())
                .param("tgUserId", participant.tgUserId())
                .param("displayName", participant.displayName())
                .param("bot", participant.bot())
                .update();
    }

    /** @return the tg_user_ids newly marked as left by this call (empty if nobody changed). */
    public List<Long> markLeft(UUID tenantId, UUID chatId, List<Long> stillPresent) {
        if (stillPresent.isEmpty()) {
            return jdbc.sql("""
                            UPDATE chat_participants SET left_at = now()
                             WHERE tenant_id = :tenantId AND chat_id = :chatId AND left_at IS NULL
                            RETURNING tg_user_id
                            """)
                    .param("tenantId", tenantId)
                    .param("chatId", chatId)
                    .query(Long.class)
                    .list();
        }
        return jdbc.sql("""
                        UPDATE chat_participants SET left_at = now()
                         WHERE tenant_id = :tenantId AND chat_id = :chatId AND left_at IS NULL
                           AND tg_user_id NOT IN (:present)
                        RETURNING tg_user_id
                        """)
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .param("present", stillPresent)
                .query(Long.class)
                .list();
    }

    /**
     * Stable fingerprint of the current human membership. A change means the set of people
     * whose consent is required has changed, which forces renewed review.
     */
    public String membershipFingerprint(UUID tenantId, UUID chatId) {
        List<Long> ids = findActive(tenantId, chatId).stream()
                .filter(p -> !p.bot())
                .map(ChatParticipant::tgUserId)
                .sorted()
                .toList();
        return Hashing.sha256Hex(ids.toString());
    }

    private ChatParticipant map(ResultSet rs, int rowNum) throws SQLException {
        return new ChatParticipant(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("chat_id", UUID.class),
                rs.getLong("tg_user_id"),
                rs.getString("display_name"),
                rs.getBoolean("is_bot"),
                Jdbc.instant(rs, "first_seen_at"),
                Jdbc.instant(rs, "left_at"));
    }
}
