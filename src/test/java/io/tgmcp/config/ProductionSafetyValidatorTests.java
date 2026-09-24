package io.tgmcp.config;

import java.util.List;

import io.tgmcp.tenancy.Tenant;
import io.tgmcp.tenancy.TenantRepository;
import io.tgmcp.telegram.gateway.TelegramAdapterKind;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Security-audit finding F10: real Telegram account access must refuse to start alongside any
 * setting that only makes sense for a local demo. Exercises {@link ProductionSafetyValidator}
 * directly (a plain constructor call, no Spring context) against every unsafe combination the
 * audit named, plus the one combination that must be allowed to start.
 */
class ProductionSafetyValidatorTests {

    private static final String KEY = "a-non-blank-value-is-enough-for-this-check";

    /** Mocked repositories reporting no leftover demo tenants/clients, for every test other
     * than the ones specifically about SEC-11's promotion preflight. Also defaults {@code
     * server.forward-headers-strategy} to "native" -- a non-issue value -- for every test other
     * than the ones specifically about SEC-13. */
    private static ProductionSafetyValidator validator(AppProperties properties, String issuer,
                                                        String publicBaseUrl, boolean cookieSecure) {
        return validator(properties, List.of(), List.of(), issuer, publicBaseUrl, cookieSecure, "native");
    }

    private static ProductionSafetyValidator validator(AppProperties properties, List<Tenant> demoTenants,
                                                        List<String> demoClientIds, String issuer,
                                                        String publicBaseUrl, boolean cookieSecure) {
        return validator(properties, demoTenants, demoClientIds, issuer, publicBaseUrl, cookieSecure, "native");
    }

    private static ProductionSafetyValidator validator(AppProperties properties, String issuer,
                                                        String publicBaseUrl, boolean cookieSecure,
                                                        String forwardHeadersStrategy) {
        return validator(properties, List.of(), List.of(), issuer, publicBaseUrl, cookieSecure,
                forwardHeadersStrategy);
    }

    private static ProductionSafetyValidator validator(AppProperties properties, List<Tenant> demoTenants,
                                                        List<String> demoClientIds, String issuer,
                                                        String publicBaseUrl, boolean cookieSecure,
                                                        String forwardHeadersStrategy) {
        TenantRepository tenants = Mockito.mock(TenantRepository.class);
        Mockito.when(tenants.findAllDemo()).thenReturn(demoTenants);
        RegisteredClientRepository clients = Mockito.mock(RegisteredClientRepository.class);
        for (String clientId : demoClientIds) {
            Mockito.when(clients.findByClientId(clientId))
                    .thenReturn(RegisteredClient.withId(clientId).clientId(clientId)
                            .clientAuthenticationMethod(
                                    org.springframework.security.oauth2.core.ClientAuthenticationMethod.NONE)
                            .authorizationGrantType(
                                    org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
                            .redirectUri("https://example.invalid/callback")
                            .build());
        }
        return new ProductionSafetyValidator(properties, tenants, clients, issuer, publicBaseUrl, cookieSecure,
                forwardHeadersStrategy);
    }

    private static AppProperties properties(boolean realAccountAccessAuthorized, boolean seedSyntheticData,
                                            String sessionEncryptionKey, String cursorSigningKey) {
        return properties(realAccountAccessAuthorized, seedSyntheticData, sessionEncryptionKey, cursorSigningKey,
                KEY);
    }

    private static AppProperties properties(boolean realAccountAccessAuthorized, boolean seedSyntheticData,
                                            String sessionEncryptionKey, String cursorSigningKey,
                                            String oauthRsaPrivateKey) {
        // Every test other than the ones specifically about SEC-07 wants that check to be a
        // non-issue, so it can focus on its own one setting; acknowledged=true here.
        return properties(realAccountAccessAuthorized, seedSyntheticData, sessionEncryptionKey, cursorSigningKey,
                oauthRsaPrivateKey, true);
    }

    private static AppProperties properties(boolean realAccountAccessAuthorized, boolean seedSyntheticData,
                                            String sessionEncryptionKey, String cursorSigningKey,
                                            String oauthRsaPrivateKey,
                                            boolean acknowledgeMissingEditDeletionPipeline) {
        return properties(realAccountAccessAuthorized, seedSyntheticData, sessionEncryptionKey, cursorSigningKey,
                oauthRsaPrivateKey, acknowledgeMissingEditDeletionPipeline, List.of());
    }

    private static AppProperties properties(boolean realAccountAccessAuthorized, boolean seedSyntheticData,
                                            String sessionEncryptionKey, String cursorSigningKey,
                                            String oauthRsaPrivateKey,
                                            boolean acknowledgeMissingEditDeletionPipeline,
                                            List<String> trustedForwardedHosts) {
        AppProperties.Telegram telegram = new AppProperties.Telegram(TelegramAdapterKind.TDLIB,
                "1", "hash", "./tdlib-data", realAccountAccessAuthorized,
                acknowledgeMissingEditDeletionPipeline);
        AppProperties.Secrets secrets = new AppProperties.Secrets(sessionEncryptionKey, "key-id", cursorSigningKey,
                oauthRsaPrivateKey, List.of());
        AppProperties.Demo demo = new AppProperties.Demo(seedSyntheticData, "demo", "Demo",
                List.of(), false);
        AppProperties.ForwardedHeaders forwardedHeaders = new AppProperties.ForwardedHeaders(trustedForwardedHosts);
        return new AppProperties(telegram, null, null, null, secrets, demo, null, forwardedHeaders, null, null);
    }

    @Test
    void allowsMockOrUnauthorizedRealModeRegardlessOfEveryOtherUnsafeSetting() {
        AppProperties dangerousExceptForAuthorization = properties(false, true, null, null);

        assertThatCode(() -> validator(dangerousExceptForAuthorization,
                "http://localhost:8080", "http://localhost:8080", false))
                .as("nothing here matters until real account access is actually authorized")
                .doesNotThrowAnyException();
    }

    @Test
    void refusesRealAccountAccessAlongsideSyntheticDemoSeeding() {
        AppProperties properties = properties(true, true, KEY, KEY);

        assertThatThrownBy(() -> validator(properties,
                "https://mcp.example.com", "https://mcp.example.com", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seed-synthetic-data");
    }

    @Test
    void refusesRealAccountAccessWithoutASessionEncryptionKey() {
        AppProperties properties = properties(true, false, null, KEY);

        assertThatThrownBy(() -> validator(properties,
                "https://mcp.example.com", "https://mcp.example.com", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("session-encryption-key");
    }

    @Test
    void refusesRealAccountAccessWithoutACursorSigningKey() {
        AppProperties properties = properties(true, false, KEY, null);

        assertThatThrownBy(() -> validator(properties,
                "https://mcp.example.com", "https://mcp.example.com", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cursor-signing-key");
    }

    @Test
    void refusesRealAccountAccessWithoutAnOauthRsaPrivateKey() {
        AppProperties properties = properties(true, false, KEY, KEY, null);

        assertThatThrownBy(() -> validator(properties,
                "https://mcp.example.com", "https://mcp.example.com", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("oauth-rsa-private-key");
    }

    @Test
    void refusesRealAccountAccessWithANonHttpsIssuer() {
        AppProperties properties = properties(true, false, KEY, KEY);

        assertThatThrownBy(() -> validator(properties,
                "http://mcp.example.com", "https://mcp.example.com", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("oauth.issuer");
    }

    @Test
    void refusesRealAccountAccessWithANonHttpsPublicBaseUrl() {
        AppProperties properties = properties(true, false, KEY, KEY);

        assertThatThrownBy(() -> validator(properties,
                "https://mcp.example.com", "http://mcp.example.com", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("public-base-url");
    }

    @Test
    void refusesRealAccountAccessWithoutSecureCookies() {
        AppProperties properties = properties(true, false, KEY, KEY);

        assertThatThrownBy(() -> validator(properties,
                "https://mcp.example.com", "https://mcp.example.com", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cookie.secure");
    }

    @Test
    void reportsEveryUnsafeSettingAtOnceRatherThanStoppingAtTheFirst() {
        AppProperties properties = properties(true, true, null, null, null);

        assertThatThrownBy(() -> validator(properties,
                "http://mcp.example.com", "http://mcp.example.com", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seed-synthetic-data")
                .hasMessageContaining("session-encryption-key")
                .hasMessageContaining("cursor-signing-key")
                .hasMessageContaining("oauth-rsa-private-key")
                .hasMessageContaining("oauth.issuer")
                .hasMessageContaining("public-base-url")
                .hasMessageContaining("cookie.secure");
    }

    /**
     * Security-audit finding SEC-07: F08 only ever logged the TDLIB adapter's unverified
     * edit/deletion pipeline as a warning -- a deployment could authorize real-account access
     * against it and start anyway. This must now refuse to start unless the operator has
     * explicitly acknowledged the gap.
     */
    @Test
    void refusesRealAccountAccessAgainstAnAdapterWithNoLiveRevisionPipelineUnlessAcknowledged() {
        AppProperties properties = properties(true, false, KEY, KEY, KEY, false);

        assertThatThrownBy(() -> validator(properties,
                "https://mcp.example.com", "https://mcp.example.com", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has never been verified against a real Telegram account")
                .hasMessageContaining("acknowledge-missing-edit-deletion-pipeline");
    }

    @Test
    void allowsRealAccountAccessAgainstAnAdapterWithNoLiveRevisionPipelineOnceAcknowledged() {
        AppProperties properties = properties(true, false, KEY, KEY, KEY, true);

        assertThatCode(() -> validator(properties,
                "https://mcp.example.com", "https://mcp.example.com", true))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsRealAccountAccessWhenEverySafetySettingIsCorrect() {
        AppProperties properties = properties(true, false, KEY, KEY);

        assertThatCode(() -> validator(properties,
                "https://mcp.example.com", "https://mcp.example.com", true))
                .doesNotThrowAnyException();
    }

    /**
     * Security-audit finding SEC-11: disabling {@code tgmcp.demo.seed-synthetic-data} stops
     * new demo records from being created, but a database reused for real-account processing
     * can still carry a demo tenant a previous run already inserted. Checked against the
     * durable {@code tenants.demo_mode} marker, independent of the currently configured demo
     * slug/email.
     */
    @Test
    void refusesRealAccountAccessWhileADemoSeededTenantStillExists() {
        AppProperties properties = properties(true, false, KEY, KEY);
        Tenant leftoverDemoTenant = new Tenant(java.util.UUID.randomUUID(), "demo", "Demo", true, null);

        assertThatThrownBy(() -> validator(properties, List.of(leftoverDemoTenant), List.of(),
                "https://mcp.example.com", "https://mcp.example.com", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Demo-seeded tenant")
                .hasMessageContaining("demo");
    }

    /**
     * Security-audit finding SEC-11: the same promotion preflight for the two fixed demo MCP
     * client ids {@link DemoDataSeeder} registers, which real provider traffic must never be
     * able to reach.
     */
    @Test
    void refusesRealAccountAccessWhileADemoRegisteredMcpClientStillExists() {
        AppProperties properties = properties(true, false, KEY, KEY);

        assertThatThrownBy(() -> validator(properties, List.of(), List.of(DemoDataSeeder.CLAUDE_DEMO_CLIENT_ID),
                "https://mcp.example.com", "https://mcp.example.com", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Demo-registered MCP client")
                .hasMessageContaining(DemoDataSeeder.CLAUDE_DEMO_CLIENT_ID);
    }

    @Test
    void allowsRealAccountAccessWhenNoDemoArtifactsRemain() {
        AppProperties properties = properties(true, false, KEY, KEY);

        assertThatCode(() -> validator(properties, List.of(), List.of(),
                "https://mcp.example.com", "https://mcp.example.com", true))
                .doesNotThrowAnyException();
    }

    /**
     * Security-audit finding SEC-13: {@code server.forward-headers-strategy=framework} trusts
     * {@code X-Forwarded-Host}/{@code -Proto}/{@code -Port} unconditionally, which the audit
     * calls an "unproven trusted-proxy boundary". Since that trust cannot be verified from
     * inside this process, the operator must demonstrate it by declaring the exact public
     * hostname(s) {@link io.tgmcp.security.TrustedForwardedHostFilter} then enforces.
     */
    @Test
    void refusesRealAccountAccessWithFrameworkForwardedHeaderTrustAndNoDeclaredHosts() {
        AppProperties properties = properties(true, false, KEY, KEY, KEY, true, List.of());

        assertThatThrownBy(() -> validator(properties, "https://mcp.example.com",
                "https://mcp.example.com", true, "framework"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forward-headers-strategy=framework")
                .hasMessageContaining("tgmcp.forwarded-headers.trusted-hosts");
    }

    @Test
    void allowsRealAccountAccessWithFrameworkForwardedHeaderTrustOnceHostsAreDeclared() {
        AppProperties properties = properties(true, false, KEY, KEY, KEY, true, List.of("mcp.example.com"));

        assertThatCode(() -> validator(properties, "https://mcp.example.com",
                "https://mcp.example.com", true, "framework"))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsRealAccountAccessWithNativeForwardedHeaderStrategyRegardlessOfDeclaredHosts() {
        AppProperties properties = properties(true, false, KEY, KEY, KEY, true, List.of());

        assertThatCode(() -> validator(properties, "https://mcp.example.com",
                "https://mcp.example.com", true, "native"))
                .doesNotThrowAnyException();
    }

    // Sanity check that the fixture builder itself produces the shape the validator expects.
    @Test
    void fixtureBuilderProducesTheExpectedTelegramConfig() {
        AppProperties properties = properties(true, false, KEY, KEY);
        assertThat(properties.telegram().realAccountAccessAuthorized()).isTrue();
        assertThat(properties.demo().seedSyntheticData()).isFalse();
    }
}
