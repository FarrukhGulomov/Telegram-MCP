package io.tgmcp.config;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import io.tgmcp.security.McpScopes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;

/**
 * Pre-registers a trusted OAuth client for each known MCP provider (Claude, ChatGPT), so that
 * the real Claude.ai / ChatGPT connectors have a way to obtain a {@code provider:*} scope at
 * all, in every deployment mode -- not only when synthetic demo data is seeded.
 *
 * <p>Security-audit finding F09's {@code rejectSelfRegisteredProviderScope} correctly refuses
 * any Dynamic Client Registration (RFC 7591) request that declares a {@code provider:*} scope
 * for itself: DCR is open and anonymous, so honouring a self-declared provider identity there
 * would let any anonymous caller register a client named "Claude" and walk away with a token
 * scoped to read content meant only for the real Claude. But every MCP tool call requires a
 * resolved provider scope to authorize anything at all (see {@code McpServerConfiguration
 * .extractContext}), and Claude's own real connector legitimately requests {@code
 * provider:anthropic_claude} once it reads that scope from this server's own RFC 9728 protected-
 * resource metadata -- so without some trusted, non-DCR path to grant it, the real connector can
 * never work here, which was a real production gap this app's own docs flagged (see
 * docs/production-prerequisites.md, "Dynamic client registration is open").
 *
 * <p>This registers the client directly through {@link RegisteredClientRepository}, the same
 * trusted path {@code DemoDataSeeder} already used for its own synthetic "claude-demo" /
 * "chatgpt-demo" clients -- never through the open DCR endpoint -- so F09's protection is
 * untouched: an anonymous DCR caller still cannot claim a provider identity for itself. The
 * genuine Claude.ai / ChatGPT connector uses this client by entering its client id directly in
 * the provider's own "manual OAuth client" connector setting (skipping DCR entirely), which is
 * exactly what Claude.ai's own connector UI offers when DCR is refused.
 */
@Component
public class TrustedMcpClientSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TrustedMcpClientSeeder.class);

    private final RegisteredClientRepository clients;

    public TrustedMcpClientSeeder(RegisteredClientRepository clients) {
        this.clients = clients;
    }

    @Override
    public void run(ApplicationArguments args) {
        registerMcpClient("claude", "Claude", McpScopes.SCOPE_ANTHROPIC_CLAUDE,
                List.of("https://claude.ai/api/mcp/auth_callback",
                        "http://localhost:8080/oauth2/callback"));
        registerMcpClient("chatgpt", "ChatGPT", McpScopes.SCOPE_OPENAI_CHATGPT,
                List.of("https://chatgpt.com/connector_platform_oauth_redirect",
                        "http://localhost:8080/oauth2/callback"));
    }

    /** Public client with PKCE and no secret, which is what a remote MCP client uses. */
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
                .scope("openid")
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
        log.info("Registered trusted MCP client '{}' with scopes [{} openid {}]", clientId, McpScopes.READ, providerScope);
    }
}
