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

@Repository
public class ChatActivationRepository {

    private static final String COLUMNS = """
            id, tenant_id, chat_id, state, epoch, purpose, provider_scope, consent_version,
            context_revision, approved_from, approved_to, retention_days, membership_fingerprint,
            pause_reason, activated_at, activated_by_user_id
            """;

    private final JdbcClient jdbc;

    public ChatActivationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ChatActivation> find(UUID tenantId, UUID chatId) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM chat_activations WHERE tenant_id = :tenantId AND chat_id = :chatId")
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .query(this::map)
                .optional();
    }

    public List<ChatActivation> findByState(UUID tenantId, ActivationState state) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM chat_activations WHERE tenant_id = :tenantId AND state = :state")
                .param("tenantId", tenantId)
                .param("state", state.name())
                .query(this::map)
                .list();
    }

    public List<ChatActivation> findAllByState(ActivationState state) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM chat_activations WHERE state = :state")
                .param("state", state.name())
                .query(this::map)
                .list();
    }

    public List<ChatActivation> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM chat_activations")
                .query(this::map)
                .list();
    }

    public void upsert(ChatActivation activation) {
        jdbc.sql("""
                        INSERT INTO chat_activations
                            (id, tenant_id, chat_id, state, epoch, purpose, provider_scope, consent_version,
                             context_revision, approved_from, approved_to, retention_days,
                             membership_fingerprint, pause_reason, activated_at, activated_by_user_id,
                             updated_at)
                        VALUES
                            (:id, :tenantId, :chatId, :state, :epoch, :purpose, :providerScope, :consentVersion,
                             :contextRevision, :approvedFrom, :approvedTo, :retentionDays,
                             :membershipFingerprint, :pauseReason, :activatedAt, :activatedByUserId, now())
                        ON CONFLICT (chat_id) DO UPDATE SET
                             state = EXCLUDED.state,
                             epoch = EXCLUDED.epoch,
                             purpose = EXCLUDED.purpose,
                             provider_scope = EXCLUDED.provider_scope,
                             consent_version = EXCLUDED.consent_version,
                             context_revision = EXCLUDED.context_revision,
                             approved_from = EXCLUDED.approved_from,
                             approved_to = EXCLUDED.approved_to,
                             retention_days = EXCLUDED.retention_days,
                             membership_fingerprint = EXCLUDED.membership_fingerprint,
                             pause_reason = EXCLUDED.pause_reason,
                             activated_at = EXCLUDED.activated_at,
                             activated_by_user_id = EXCLUDED.activated_by_user_id,
                             updated_at = now()
                        """)
                .param("id", activation.id())
                .param("tenantId", activation.tenantId())
                .param("chatId", activation.chatId())
                .param("state", activation.state().name())
                .param("epoch", activation.epoch())
                .param("purpose", activation.purpose())
                .param("providerScope", activation.providerScope())
                .param("consentVersion", activation.consentVersion())
                .param("contextRevision", activation.contextRevision())
                .param("approvedFrom", java.sql.Timestamp.from(activation.approvedFrom()))
                .param("approvedTo", java.sql.Timestamp.from(activation.approvedTo()))
                .param("retentionDays", activation.retentionDays())
                .param("membershipFingerprint", activation.membershipFingerprint())
                .param("pauseReason", activation.pauseReason())
                .param("activatedAt", activation.activatedAt() == null
                        ? null : java.sql.Timestamp.from(activation.activatedAt()))
                .param("activatedByUserId", activation.activatedByUserId())
                .update();
    }

    public void updateState(UUID tenantId, UUID chatId, ActivationState state, String reason) {
        jdbc.sql("""
                        UPDATE chat_activations
                           SET state = :state, pause_reason = :reason, updated_at = now()
                         WHERE tenant_id = :tenantId AND chat_id = :chatId
                        """)
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .param("state", state.name())
                .param("reason", reason)
                .update();
    }

    /**
     * Revokes the activation and bumps its fencing epoch in the same statement, so any sync
     * run already holding an older epoch value is detectable as stale even if the chat is
     * later re-proposed and re-activated (security audit finding F07).
     */
    public void revoke(UUID tenantId, UUID chatId, String reason) {
        jdbc.sql("""
                        UPDATE chat_activations
                           SET state = 'REVOKED', pause_reason = :reason, epoch = epoch + 1, updated_at = now()
                         WHERE tenant_id = :tenantId AND chat_id = :chatId
                        """)
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .param("reason", reason)
                .update();
    }

    public void updateMembershipFingerprint(UUID tenantId, UUID chatId, String fingerprint) {
        jdbc.sql("""
                        UPDATE chat_activations
                           SET membership_fingerprint = :fingerprint, updated_at = now()
                         WHERE tenant_id = :tenantId AND chat_id = :chatId
                        """)
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .param("fingerprint", fingerprint)
                .update();
    }

    private ChatActivation map(ResultSet rs, int rowNum) throws SQLException {
        return new ChatActivation(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("chat_id", UUID.class),
                ActivationState.valueOf(rs.getString("state")),
                rs.getLong("epoch"),
                rs.getString("purpose"),
                rs.getString("provider_scope"),
                rs.getString("consent_version"),
                rs.getString("context_revision"),
                Jdbc.instant(rs, "approved_from"),
                Jdbc.instant(rs, "approved_to"),
                rs.getInt("retention_days"),
                rs.getString("membership_fingerprint"),
                rs.getString("pause_reason"),
                Jdbc.instant(rs, "activated_at"),
                rs.getObject("activated_by_user_id", UUID.class));
    }
}
