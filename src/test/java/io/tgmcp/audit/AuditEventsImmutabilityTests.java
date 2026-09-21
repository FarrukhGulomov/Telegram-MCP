package io.tgmcp.audit;

import java.util.Map;
import java.util.UUID;

import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.tenancy.ActorType;
import io.tgmcp.tenancy.Tenant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Security-audit finding SEC-R09: audit_events must be tamper-evident at the database level,
 * not merely by convention -- nothing in application code ever updates or deletes an
 * individual row (see {@link AuditService}, which only ever inserts), so a database trigger
 * refusing both outright (migration V11) costs the application nothing while closing off
 * tampering by anyone with direct database access. The one deliberate exception, a demo
 * tenant's full erasure cascading into its own audit trail, is covered by
 * {@code DemoDataCleanupRunnerTests}.
 */
class AuditEventsImmutabilityTests extends AbstractIntegrationTest {

    @Autowired
    private AuditService auditService;

    @Autowired
    private io.tgmcp.tenancy.TenantRepository tenants;

    private UUID seedTenantAndAuditRow() {
        UUID tenantId = UUID.randomUUID();
        this.tenants.insert(new Tenant(tenantId, "audit-immutability-" + tenantId, "Audit Test", false, null));
        this.auditService.record(tenantId, ActorType.SYSTEM, "test", "TEST_ACTION", null,
                AuditService.ALLOWED, Map.of());
        return tenantId;
    }

    @Test
    void refusesToUpdateAnAuditEventRow() {
        UUID tenantId = seedTenantAndAuditRow();

        assertThatThrownBy(() -> this.jdbcTemplate.update(
                "UPDATE audit_events SET outcome = 'DENIED' WHERE tenant_id = ?", tenantId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    void refusesToDirectlyDeleteAnAuditEventRow() {
        UUID tenantId = seedTenantAndAuditRow();

        assertThatThrownBy(() -> this.jdbcTemplate.update(
                "DELETE FROM audit_events WHERE tenant_id = ?", tenantId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("cannot be deleted directly");

        Integer remaining = this.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_events WHERE tenant_id = ?", Integer.class, tenantId);
        assertThat(remaining).isEqualTo(1);
    }
}
