package io.tgmcp.config;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import io.tgmcp.common.Ids;
import io.tgmcp.security.McpScopes;
import io.tgmcp.tenancy.AppUser;
import io.tgmcp.tenancy.AppUserRepository;
import io.tgmcp.tenancy.Tenant;
import io.tgmcp.tenancy.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;

/**
 * Creates the demo tenant, its owner, and two demo-only MCP clients.
 *
 * <p>Everything here is idempotent, so a restart neither duplicates nor resets anything. The
 * real, non-demo client identities Claude/ChatGPT use regardless of whether synthetic demo data
 * is seeded live separately in {@link TrustedMcpClientSeeder}; the two clients registered here
 * ({@link #CLAUDE_DEMO_CLIENT_ID}, {@link #CHATGPT_DEMO_CLIENT_ID}) exist only for local/demo
 * use and are exactly the ones {@link ProductionSafetyValidator} checks are gone before real
 * account access is authorized.
 */
@Component
@ConditionalOnProperty(name = "tgmcp.demo.seed-synthetic-data", havingValue = "true", matchIfMissing = true)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    /**
     * Security-audit finding SEC-11: fixed identifiers for the demo MCP clients this class
     * seeds, referenced by {@link ProductionSafetyValidator} to detect their presence
     * independent of whatever the currently configured demo email/slug happen to be.
     */
    public static final String CLAUDE_DEMO_CLIENT_ID = "claude-demo";
    public static final String CHATGPT_DEMO_CLIENT_ID = "chatgpt-demo";

    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final RegisteredClientRepository clients;
    private final AppProperties properties;

    public DemoDataSeeder(TenantRepository tenants, AppUserRepository users,
                          RegisteredClientRepository clients, AppProperties properties) {
        this.tenants = tenants;
        this.users = users;
        this.clients = clients;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        AppProperties.Demo demo = this.properties.demo();
        // There is no password, and no phone number this seeder can plausibly complete a real
        // Telegram login with -- like every other account, the demo owner needs one linked
        // before it can actually sign in, which this seeder deliberately does not do on its
        // own (see the "hozircha alohida hal qilamiz" deferral). Its phone_number stays null,
        // so it is created exactly once, alongside its tenant, never re-checked afterward.
        this.tenants.findBySlug(demo.tenantSlug()).ifPresentOrElse(existing -> { }, () -> {
            UUID tenantId = Ids.newId();
            this.tenants.insert(new Tenant(tenantId, demo.tenantSlug(), demo.tenantName(), true, null));
            this.users.insert(new AppUser(Ids.newId(), tenantId, null, "Demo Owner", "OWNER", true, null, null));
            log.info("Created demo tenant '{}' with its owner", demo.tenantSlug());
        });

        // A second tenant, so that cross-tenant isolation is observable in the running app and
        // not only in tests.
        this.tenants.findBySlug("other").ifPresentOrElse(existing -> { }, () -> {
            UUID otherId = Ids.newId();
            this.tenants.insert(new Tenant(otherId, "other", "Second Synthetic Tenant", true, null));
            this.users.insert(new AppUser(Ids.newId(), otherId, null, "Other Owner", "OWNER", true, null, null));
        });

        registerMcpClient(CLAUDE_DEMO_CLIENT_ID, "Claude (demo registration)",
                McpScopes.SCOPE_ANTHROPIC_CLAUDE,
                List.of("https://claude.ai/api/mcp/auth_callback",
                        "http://localhost:8080/oauth2/callback"));
        registerMcpClient(CHATGPT_DEMO_CLIENT_ID, "ChatGPT (demo registration)",
                McpScopes.SCOPE_OPENAI_CHATGPT,
                List.of("https://chatgpt.com/connector_platform_oauth_redirect",
                        "http://localhost:8080/oauth2/callback"));
    }

    /**
     * Public client with PKCE and no secret, which is what a remote MCP client uses.
     * Real clients may instead register themselves through the RFC 7591 endpoint.
     */
    private void registerMcpClient(String clientId, String clientName, String providerScope,
                                   List<String> redirectUris) {
        if (this.clients.findByClientId(clientId) != null) {
            return;
        }
        RegisteredClient.Builder builder = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientName(clientName)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .scope(McpScopes.READ)
                .scope(providerScope)
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(true)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(30))
                        .reuseRefreshTokens(false)
                        .build());
        redirectUris.forEach(builder::redirectUri);
        this.clients.save(builder.build());
        log.info("Registered demo MCP client '{}' with scopes [{} {}]", clientId, McpScopes.READ, providerScope);
    }
}
