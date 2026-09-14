package io.tgmcp;

import java.util.Map;
import java.util.UUID;

import io.tgmcp.audit.AuditService;
import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Security-audit finding F20: {@code AuditService.record(AuthenticatedActor, ...)} and {@code
 * system(...)} both called the {@code @Transactional(REQUIRES_NEW)} 7-arg {@code record(...)}
 * overload directly on {@code this} -- a same-bean self-invocation that bypasses the Spring AOP
 * proxy and so silently drops the annotation. Since production code only ever reaches the 7-arg
 * overload through one of those two paths, REQUIRES_NEW -- whose entire purpose is that an audit
 * row survives a surrounding transaction that later rolls back -- was never actually applied.
 * {@link AuditService} now uses an imperative {@link TransactionTemplate}, which cannot be
 * defeated by an internal call the way the annotation was. These tests reproduce the exact
 * failure mode: call {@code record}/{@code system} from inside an outer transaction, roll that
 * transaction back, and assert the audit row is still there.
 */
class AuditServiceTests extends AbstractIntegrationTest {

    @Autowired
    private AuditService auditService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void recordSurvivesTheEnclosingTransactionBeingRolledBack() {
        Fixtures.Team team = this.fixtures.team("f20-record");
        UUID chatId = UUID.randomUUID();
        TransactionTemplate outer = new TransactionTemplate(this.transactionManager);

        assertThatRollingBackAnOuterTransactionStillLeavesAnAuditRow(outer, () ->
                this.auditService.record(team.actor(), "F20_TEST_ACTION", chatId, AuditService.ALLOWED,
                        Map.of("k", "v")),
                team.tenant().id(), "F20_TEST_ACTION");
    }

    @Test
    void systemSurvivesTheEnclosingTransactionBeingRolledBack() {
        Fixtures.Team team = this.fixtures.team("f20-system");
        UUID chatId = UUID.randomUUID();
        TransactionTemplate outer = new TransactionTemplate(this.transactionManager);

        assertThatRollingBackAnOuterTransactionStillLeavesAnAuditRow(outer, () ->
                this.auditService.system(team.tenant().id(), "F20_TEST_SYSTEM_ACTION", chatId,
                        AuditService.ALLOWED, Map.of("k", "v")),
                team.tenant().id(), "F20_TEST_SYSTEM_ACTION");
    }

    private void assertThatRollingBackAnOuterTransactionStillLeavesAnAuditRow(TransactionTemplate outer,
                                                                              Runnable auditCall,
                                                                              UUID tenantId, String action) {
        class DeliberateRollback extends RuntimeException {
        }

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            auditCall.run();
            throw new DeliberateRollback();
        })).isInstanceOf(DeliberateRollback.class);

        Integer count = this.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_events WHERE tenant_id = ? AND action = ?",
                Integer.class, tenantId, action);
        assertThat(count).as("REQUIRES_NEW must commit the audit row independently of the "
                        + "enclosing (rolled-back) transaction")
                .isEqualTo(1);
    }

}
