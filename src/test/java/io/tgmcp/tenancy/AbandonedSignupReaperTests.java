package io.tgmcp.tenancy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.TestClockConfiguration;
import io.tgmcp.telegram.TelegramConnection;
import io.tgmcp.telegram.TelegramConnectionRepository;
import io.tgmcp.telegram.gateway.LoginStage;
import io.tgmcp.telegram.gateway.TelegramAdapterKind;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security-audit finding SEC-R08: {@code PreAuthLoginService#createAccount} creates a
 * permanent tenant/user row for a new phone number before Telegram's own login ever proves
 * the visitor holds it. {@link AbandonedSignupReaper} is the remediation -- these tests pin
 * exactly what it may and may not delete.
 */
@TestPropertySource(properties = {
        "tgmcp.abuse-controls.abandoned-signup-max-age=PT1H",
        // AbandonedSignupReaper is now @ConditionalOnProperty on its class (fixing security-audit
        // finding L2 -- the annotation had no effect on its @Scheduled method), so it needs this
        // override to even exist as a bean under the test profile's tgmcp.sync.scheduled=false
        // default. Its own @Scheduled trigger is never exercised by this test class either way --
        // reap() is always called directly.
        "tgmcp.sync.scheduled=true"
})
class AbandonedSignupReaperTests extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private TelegramConnectionRepository connections;

    @Autowired
    private AbandonedSignupReaper reaper;

    private UUID insertRealTenant(String slug, Instant createdAt) {
        UUID tenantId = UUID.randomUUID();
        this.tenants.insert(new Tenant(tenantId, slug, slug, false, null));
        this.jdbcTemplate.update("UPDATE tenants SET created_at = ? WHERE id = ?",
                Timestamp.from(createdAt), tenantId);
        return tenantId;
    }

    @Test
    void deletesARealTenantWhoseLoginNeverReachedReady() {
        UUID tenantId = insertRealTenant("abandoned",
                TestClockConfiguration.START.minus(Duration.ofHours(2)));

        this.reaper.reap();

        assertThat(this.tenants.findById(tenantId)).isEmpty();
    }

    @Test
    void leavesARecentlyStartedSignupAlone() {
        UUID tenantId = insertRealTenant("just-started",
                TestClockConfiguration.START.minus(Duration.ofMinutes(10)));

        this.reaper.reap();

        assertThat(this.tenants.findById(tenantId)).isPresent();
    }

    @Test
    void neverDeletesATenantWhoseConnectionOnceReachedReadyEvenIfNoLongerReadyNow() {
        UUID tenantId = insertRealTenant("once-real",
                TestClockConfiguration.START.minus(Duration.ofDays(30)));
        UUID userId = UUID.randomUUID();
        this.users.insert(new AppUser(userId, tenantId, "+15551234567", "Owner", "OWNER", true, null, null));
        UUID connectionId = UUID.randomUUID();
        this.connections.insert(new TelegramConnection(connectionId, tenantId, userId,
                TelegramAdapterKind.MOCK, LoginStage.WAIT_CODE, null, null, null, "test", null, null, null));
        // Proved real identity once, long ago...
        this.connections.updateStatus(tenantId, connectionId, LoginStage.READY, 42L, "owner", "+15551234567", null);
        // ...then lost authorization later. connected_at must still record that it once happened.
        this.connections.updateStatus(tenantId, connectionId, LoginStage.DISCONNECTED, null, null, null,
                "logged out remotely");

        this.reaper.reap();

        assertThat(this.tenants.findById(tenantId)).isPresent();
    }

    @Test
    void leavesDemoTenantsAlone() {
        UUID tenantId = UUID.randomUUID();
        this.tenants.insert(new Tenant(tenantId, "old-demo", "Old Demo", true, null));
        this.jdbcTemplate.update("UPDATE tenants SET created_at = ? WHERE id = ?",
                Timestamp.from(TestClockConfiguration.START.minus(Duration.ofDays(1))), tenantId);

        this.reaper.reap();

        assertThat(this.tenants.findById(tenantId)).isPresent();
    }
}
