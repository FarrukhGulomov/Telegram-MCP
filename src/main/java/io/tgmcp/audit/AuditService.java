package io.tgmcp.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.tgmcp.common.Ids;
import io.tgmcp.tenancy.ActorType;
import io.tgmcp.tenancy.AuthenticatedActor;
import io.tgmcp.common.Jdbc;
import tools.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only audit trail for access metadata, consent changes, sync failures and
 * revocations. Deliberately stores no message text and no credentials.
 */
@Service
public class AuditService {

    public static final String ALLOWED = "ALLOWED";
    public static final String DENIED = "DENIED";
    public static final String ERROR = "ERROR";

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public AuditService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID tenantId, ActorType actorType, String actorId, String action,
                       UUID chatId, String outcome, Map<String, Object> detail) {
        jdbc.sql("""
                        INSERT INTO audit_events (id, tenant_id, actor_type, actor_id, action, chat_id, outcome, detail)
                        VALUES (:id, :tenantId, :actorType, :actorId, :action, :chatId, :outcome, CAST(:detail AS jsonb))
                        """)
                .param("id", Ids.newId())
                .param("tenantId", tenantId)
                .param("actorType", actorType.name())
                .param("actorId", actorId)
                .param("action", action)
                .param("chatId", chatId)
                .param("outcome", outcome)
                .param("detail", objectMapper.writeValueAsString(detail == null ? Map.of() : detail))
                .update();
    }

    public void record(AuthenticatedActor actor, String action, UUID chatId, String outcome,
                       Map<String, Object> detail) {
        record(actor.tenantId(), actor.actorType(), actor.auditActorId(), action, chatId, outcome, detail);
    }

    public void system(UUID tenantId, String action, UUID chatId, String outcome, Map<String, Object> detail) {
        record(tenantId, ActorType.SYSTEM, "system", action, chatId, outcome, detail);
    }

    public List<AuditEvent> recent(UUID tenantId, int limit) {
        return jdbc.sql("""
                        SELECT id, tenant_id, occurred_at, actor_type, actor_id, action, chat_id, outcome,
                               detail::text AS detail_json
                        FROM audit_events
                        WHERE tenant_id = :tenantId
                        ORDER BY occurred_at DESC
                        LIMIT :limit
                        """)
                .param("tenantId", tenantId)
                .param("limit", Math.clamp(limit, 1, 500))
                .query((rs, rowNum) -> new AuditEvent(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        Jdbc.instant(rs, "occurred_at"),
                        ActorType.valueOf(rs.getString("actor_type")),
                        rs.getString("actor_id"),
                        rs.getString("action"),
                        rs.getObject("chat_id", UUID.class),
                        rs.getString("outcome"),
                        readDetail(rs.getString("detail_json"))))
                .list();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readDetail(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        }
        catch (RuntimeException ex) {
            return Map.of();
        }
    }
}
