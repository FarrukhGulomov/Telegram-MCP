package io.tgmcp.security;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * Dynamic Client Registration (RFC 7591) is open and anonymous, so a self-registering client's
 * declared scope is never trusted to include a {@code provider:*} identity (security-audit
 * finding F09, see {@link AuthorizationServerConfig#rejectSelfRegisteredProviderScope}). In
 * practice the real ChatGPT connector's registration request omits {@code scope} entirely
 * (confirmed from a real Railway deployment's logs) -- without a default, that persists as a
 * client with no scope whatsoever, which can complete OAuth but can never make a single MCP
 * call, indistinguishable from "connecting" having silently done nothing.
 *
 * <p>This decorator fills in a safe default ({@code mcp:read} + {@code openid} -- granting no
 * chat-content access, exactly as the previously-manual "no scope in the request" fallback in
 * {@link AuthorizationServerConfig} already treats a registered client with a real scope) for a
 * newly self-registered client that declared none. Separately, when at least one of the
 * client's own redirect URIs resolves to a domain this deployment trusts to be operated by a
 * specific provider, it also grants that one {@code provider:*} scope. The provider identity
 * still never comes from anything the client asserts about itself: an authorization code for
 * that redirect URI can only ever be delivered to whoever actually controls that domain, so this
 * relies on the same trust boundary the OAuth redirect step already depends on, not a new one.
 * A client that already carries a {@code provider:*} scope (only ever true for {@link
 * io.tgmcp.config.TrustedMcpClientSeeder}'s direct, non-DCR registrations -- DCR itself already
 * rejects a self-declared one) is left untouched.
 */
final class TrustedProviderAugmentingClientRepository implements RegisteredClientRepository {

    private static final Logger log = LoggerFactory.getLogger(TrustedProviderAugmentingClientRepository.class);

    private static final Set<String> DEFAULT_SELF_REGISTERED_SCOPES = Set.of(McpScopes.READ, "openid");

    /** Domains verified to be operated by the named provider's own connector platform. */
    private static final Map<String, String> TRUSTED_PROVIDER_DOMAINS = Map.of(
            "chatgpt.com", McpScopes.SCOPE_OPENAI_CHATGPT,
            "claude.ai", McpScopes.SCOPE_ANTHROPIC_CLAUDE);

    private final RegisteredClientRepository delegate;

    TrustedProviderAugmentingClientRepository(RegisteredClientRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        this.delegate.save(augment(registeredClient));
    }

    @Override
    public RegisteredClient findById(String id) {
        return this.delegate.findById(id);
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        return this.delegate.findByClientId(clientId);
    }

    private RegisteredClient augment(RegisteredClient registeredClient) {
        Set<String> existingScopes = registeredClient.getScopes();
        boolean alreadyHasProviderScope = existingScopes.stream().anyMatch(scope -> scope.startsWith(McpScopes.PROVIDER_PREFIX));
        if (alreadyHasProviderScope) {
            return registeredClient;
        }

        Set<String> trustedProviderScopes = trustedProviderScopesFor(registeredClient);
        if (existingScopes.isEmpty() && trustedProviderScopes.isEmpty()) {
            log.info("Self-registered client {} declared no scope; defaulting to {}",
                    registeredClient.getClientId(), DEFAULT_SELF_REGISTERED_SCOPES);
            return withScopes(registeredClient, DEFAULT_SELF_REGISTERED_SCOPES);
        }
        if (trustedProviderScopes.isEmpty()) {
            return registeredClient;
        }

        Set<String> augmented = new LinkedHashSet<>(existingScopes.isEmpty() ? DEFAULT_SELF_REGISTERED_SCOPES : existingScopes);
        augmented.addAll(trustedProviderScopes);
        log.info("Self-registered client {} redirect URI matches a trusted provider domain; granting {}",
                registeredClient.getClientId(), trustedProviderScopes);
        return withScopes(registeredClient, augmented);
    }

    private static Set<String> trustedProviderScopesFor(RegisteredClient registeredClient) {
        Set<String> matched = new LinkedHashSet<>();
        for (String redirectUri : registeredClient.getRedirectUris()) {
            String scope = TRUSTED_PROVIDER_DOMAINS.get(hostOf(redirectUri));
            if (scope != null) {
                matched.add(scope);
            }
        }
        return matched;
    }

    private static String hostOf(String redirectUri) {
        try {
            return URI.create(redirectUri).getHost();
        }
        catch (RuntimeException ex) {
            return null;
        }
    }

    private static RegisteredClient withScopes(RegisteredClient registeredClient, Set<String> scopes) {
        return RegisteredClient.from(registeredClient)
                .scopes(existing -> {
                    existing.clear();
                    existing.addAll(scopes);
                })
                .build();
    }
}
