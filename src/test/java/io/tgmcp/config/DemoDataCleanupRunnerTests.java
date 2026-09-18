package io.tgmcp.config;

import java.util.List;
import java.util.UUID;

import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.tenancy.Tenant;
import io.tgmcp.tenancy.TenantRepository;
import io.tgmcp.telegram.gateway.TelegramAdapterKind;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security-audit finding SEC-11's remediation: {@link ProductionSafetyValidator} refuses to
 * start real-account access while a demo-seeded tenant or the two fixed demo OAuth client ids
 * are still present, but disabling {@code tgmcp.demo.seed-synthetic-data} does not delete rows
 * a previous run already created. {@link DemoDataCleanupRunner} is the explicit, off-by-default
 * action that does. Uses a real PostgreSQL (see {@link AbstractIntegrationTest}) because it
 * exercises the tenant-cascade delete and the OAuth tables' schema directly, not through any
 * repository abstraction.
 */
class DemoDataCleanupRunnerTests extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private RegisteredClientRepository clients;

    private AppProperties properties(boolean cleanupEnabled) {
        AppProperties.Telegram telegram = new AppProperties.Telegram(TelegramAdapterKind.MOCK, null, null,
                "./tdlib-data", false, true);
        AppProperties.Demo demo = new AppProperties.Demo(false, "demo", "Demo", "owner@demo.invalid", "pw",
                List.of(), cleanupEnabled);
        return new AppProperties(telegram, null, null, null, null, demo, null, null, null);
    }

    private RegisteredClient registerClient(String clientId) {
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientName(clientId)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .scope("mcp:read")
                .redirectUri("https://example.invalid/callback")
                .build();
        this.clients.save(client);
        return client;
    }

    private void grantAuthorizationAndConsent(RegisteredClient client, String principal) {
        this.jdbcTemplate.update("""
                INSERT INTO oauth2_authorization (id, registered_client_id, principal_name,
                        authorization_grant_type, authorized_scopes)
                VALUES (?, ?, ?, 'authorization_code', 'mcp:read')
                """, UUID.randomUUID().toString(), client.getId(), principal);
        this.jdbcTemplate.update("""
                INSERT INTO oauth2_authorization_consent (registered_client_id, principal_name, authorities)
                VALUES (?, ?, 'SCOPE_mcp:read')
                """, client.getId(), principal);
    }

    private int countAuthorizationsFor(RegisteredClient client) {
        Integer count = this.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM oauth2_authorization WHERE registered_client_id = ?", Integer.class,
                client.getId());
        return count == null ? 0 : count;
    }

    private int countConsentsFor(RegisteredClient client) {
        Integer count = this.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM oauth2_authorization_consent WHERE registered_client_id = ?", Integer.class,
                client.getId());
        return count == null ? 0 : count;
    }

    @Test
    void doesNothingWhenDisabled() {
        UUID tenantId = UUID.randomUUID();
        this.tenants.insert(new Tenant(tenantId, "leftover-demo", "Leftover Demo", true, null));
        RegisteredClient claudeDemo = registerClient(DemoDataSeeder.CLAUDE_DEMO_CLIENT_ID);
        grantAuthorizationAndConsent(claudeDemo, "someone@example.invalid");

        new DemoDataCleanupRunner(properties(false), this.jdbcTemplate);

        assertThat(this.tenants.findById(tenantId)).isPresent();
        assertThat(this.clients.findByClientId(DemoDataSeeder.CLAUDE_DEMO_CLIENT_ID)).isNotNull();
        assertThat(countAuthorizationsFor(claudeDemo)).isEqualTo(1);
    }

    @Test
    void removesEveryDemoSeededTenantButLeavesOthersUntouched() {
        UUID demoTenantId = UUID.randomUUID();
        this.tenants.insert(new Tenant(demoTenantId, "leftover-demo", "Leftover Demo", true, null));
        UUID realTenantId = UUID.randomUUID();
        this.tenants.insert(new Tenant(realTenantId, "real-team", "Real Team", false, null));

        new DemoDataCleanupRunner(properties(true), this.jdbcTemplate);

        assertThat(this.tenants.findById(demoTenantId)).isEmpty();
        assertThat(this.tenants.findById(realTenantId)).isPresent();
    }

    @Test
    void removesTheFixedDemoOauthClientsAndTheirOrphanedAuthorizationRowsButLeavesOthersUntouched() {
        RegisteredClient claudeDemo = registerClient(DemoDataSeeder.CLAUDE_DEMO_CLIENT_ID);
        grantAuthorizationAndConsent(claudeDemo, "someone@example.invalid");
        RegisteredClient chatgptDemo = registerClient(DemoDataSeeder.CHATGPT_DEMO_CLIENT_ID);
        grantAuthorizationAndConsent(chatgptDemo, "someone-else@example.invalid");
        RegisteredClient realClient = registerClient("a-real-registered-client");
        grantAuthorizationAndConsent(realClient, "real-user@example.invalid");

        new DemoDataCleanupRunner(properties(true), this.jdbcTemplate);

        assertThat(this.clients.findByClientId(DemoDataSeeder.CLAUDE_DEMO_CLIENT_ID)).isNull();
        assertThat(this.clients.findByClientId(DemoDataSeeder.CHATGPT_DEMO_CLIENT_ID)).isNull();
        assertThat(countAuthorizationsFor(claudeDemo)).isZero();
        assertThat(countConsentsFor(claudeDemo)).isZero();
        assertThat(countAuthorizationsFor(chatgptDemo)).isZero();
        assertThat(countConsentsFor(chatgptDemo)).isZero();

        assertThat(this.clients.findByClientId("a-real-registered-client")).isNotNull();
        assertThat(countAuthorizationsFor(realClient)).isEqualTo(1);
        assertThat(countConsentsFor(realClient)).isEqualTo(1);
    }

    @Test
    void isSafeToRunAgainOnceThereIsNothingLeftToClean() {
        this.tenants.insert(new Tenant(UUID.randomUUID(), "leftover-demo", "Leftover Demo", true, null));
        registerClient(DemoDataSeeder.CLAUDE_DEMO_CLIENT_ID);

        new DemoDataCleanupRunner(properties(true), this.jdbcTemplate);
        // The database is already clean; a second run must not throw and must delete nothing.
        new DemoDataCleanupRunner(properties(true), this.jdbcTemplate);

        assertThat(this.tenants.findAllDemo()).isEmpty();
        assertThat(this.clients.findByClientId(DemoDataSeeder.CLAUDE_DEMO_CLIENT_ID)).isNull();
    }
}
