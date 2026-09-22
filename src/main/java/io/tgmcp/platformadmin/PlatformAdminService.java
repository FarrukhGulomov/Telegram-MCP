package io.tgmcp.platformadmin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.tgmcp.common.Jdbc;
import io.tgmcp.tenancy.ActorType;
import tools.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Cross-tenant read model for the platform-admin overview: every tenant's footprint, plus a
 * merged audit feed spanning all of them (including a filtered "suspicious activity" view).
 * Deliberately separate from every tenant-scoped repository -- nothing here is reachable except
 * through {@code PlatformAdminController}, itself gated by {@link
 * io.tgmcp.ui.CurrentActor#requirePlatformAdmin()}.
 */
@Service
public class PlatformAdminService {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public PlatformAdminService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<TenantSummary> tenantSummaries() {
        return this.jdbc.sql("""
                        SELECT t.id AS tenant_id, t.slug, t.name, t.demo_mode, t.created_at,
                               COUNT(DISTINCT u.id) AS user_count,
                               COUNT(DISTINCT c.id) AS chat_count,
                               COUNT(DISTINCT c.id) FILTER (WHERE ca.state = 'ACTIVE') AS active_chat_count,
                               COUNT(DISTINCT tc.id) FILTER (WHERE tc.status = 'READY') AS connected_accounts
                          FROM tenants t
                          LEFT JOIN app_users u ON u.tenant_id = t.id
                          LEFT JOIN chats c ON c.tenant_id = t.id
                          LEFT JOIN chat_activations ca ON ca.chat_id = c.id
                          LEFT JOIN telegram_connections tc ON tc.tenant_id = t.id
                         GROUP BY t.id, t.slug, t.name, t.demo_mode, t.created_at
                         ORDER BY t.created_at DESC
                        """)
                .query((rs, rowNum) -> new TenantSummary(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("slug"),
                        rs.getString("name"),
                        rs.getBoolean("demo_mode"),
                        Jdbc.instant(rs, "created_at"),
                        rs.getLong("user_count"),
                        rs.getLong("chat_count"),
                        rs.getLong("active_chat_count"),
                        rs.getLong("connected_accounts")))
                .list();
    }

    public List<PlatformAuditEvent> recentEvents(int limit) {
        return events(false, limit);
    }

    /**
     * A denied or errored access attempt, or a rate-limited one, across ANY tenant -- the
     * closest thing to a "did anything suspicious happen" signal this application currently
     * records (see {@link io.tgmcp.audit.AuditAction}; there is no separate anomaly-detection
     * pipeline beyond this).
     */
    public List<PlatformAuditEvent> recentSuspiciousEvents(int limit) {
        return events(true, limit);
    }

    private List<PlatformAuditEvent> events(boolean suspiciousOnly, int limit) {
        return this.jdbc.sql("""
                        SELECT e.id, t.slug AS tenant_slug, e.occurred_at, e.actor_type, e.actor_id,
                               e.action, e.chat_id, e.outcome, e.detail::text AS detail_json
                          FROM audit_events e
                          JOIN tenants t ON t.id = e.tenant_id
                         WHERE (:suspiciousOnly = false)
                            OR (e.outcome IN ('DENIED', 'ERROR') OR e.action = 'MCP_RATE_LIMITED')
                         ORDER BY e.occurred_at DESC
                         LIMIT :limit
                        """)
                .param("suspiciousOnly", suspiciousOnly)
                .param("limit", Math.clamp(limit, 1, 500))
                .query((rs, rowNum) -> new PlatformAuditEvent(
                        rs.getObject("id", UUID.class),
                        rs.getString("tenant_slug"),
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
            return this.objectMapper.readValue(json, Map.class);
        }
        catch (RuntimeException ex) {
            return Map.of();
        }
    }
}
