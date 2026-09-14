package io.tgmcp.consent;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.tgmcp.common.Jdbc;

@Repository
public class ConsentRepository {

    private static final String COLUMNS = """
            id, tenant_id, chat_id, participant_tg_user_id, participant_display_name, purpose,
            provider_scope, consent_version, method, evidence_ref, granted_at, expires_at,
            revoked_at, revoked_reason, recorded_by_user_id
            """;

    private final JdbcClient jdbc;

    public ConsentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ConsentRecord> findForChat(UUID tenantId, UUID chatId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM consent_records
                        WHERE tenant_id = :tenantId AND chat_id = :chatId
                        ORDER BY participant_tg_user_id, granted_at DESC
                        """)
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .query(this::map)
                .list();
    }

    public void insert(ConsentRecord record) {
        jdbc.sql("""
                        INSERT INTO consent_records
                            (id, tenant_id, chat_id, participant_tg_user_id, participant_display_name,
                             purpose, provider_scope, consent_version, method, evidence_ref,
                             granted_at, expires_at, recorded_by_user_id)
                        VALUES
                            (:id, :tenantId, :chatId, :participantTgUserId, :participantDisplayName,
                             :purpose, :providerScope, :consentVersion, :method, :evidenceRef,
                             :grantedAt, :expiresAt, :recordedByUserId)
                        """)
                .param("id", record.id())
                .param("tenantId", record.tenantId())
                .param("chatId", record.chatId())
                .param("participantTgUserId", record.participantTgUserId())
                .param("participantDisplayName", record.participantDisplayName())
                .param("purpose", record.purpose())
                .param("providerScope", record.providerScope())
                .param("consentVersion", record.consentVersion())
                .param("method", record.method().name())
                .param("evidenceRef", record.evidenceRef())
                .param("grantedAt", java.sql.Timestamp.from(record.grantedAt()))
                .param("expiresAt", record.expiresAt() == null ? null : java.sql.Timestamp.from(record.expiresAt()))
                .param("recordedByUserId", record.recordedByUserId())
                .update();
    }

    /** Revokes every live consent of one participant in one chat. Idempotent. */
    public int revokeParticipant(UUID tenantId, UUID chatId, long participantTgUserId, String reason) {
        return jdbc.sql("""
                        UPDATE consent_records
                           SET revoked_at = now(), revoked_reason = :reason
                         WHERE tenant_id = :tenantId AND chat_id = :chatId
                           AND participant_tg_user_id = :participant AND revoked_at IS NULL
                        """)
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .param("participant", participantTgUserId)
                .param("reason", reason)
                .update();
    }

    public int revokeChat(UUID tenantId, UUID chatId, String reason) {
        return jdbc.sql("""
                        UPDATE consent_records
                           SET revoked_at = now(), revoked_reason = :reason
                         WHERE tenant_id = :tenantId AND chat_id = :chatId AND revoked_at IS NULL
                        """)
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .param("reason", reason)
                .update();
    }

    private ConsentRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new ConsentRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("chat_id", UUID.class),
                rs.getLong("participant_tg_user_id"),
                rs.getString("participant_display_name"),
                rs.getString("purpose"),
                rs.getString("provider_scope"),
                rs.getString("consent_version"),
                ConsentMethod.valueOf(rs.getString("method")),
                rs.getString("evidence_ref"),
                Jdbc.instant(rs, "granted_at"),
                Jdbc.instant(rs, "expires_at"),
                Jdbc.instant(rs, "revoked_at"),
                rs.getString("revoked_reason"),
                rs.getObject("recorded_by_user_id", UUID.class));
    }
}
