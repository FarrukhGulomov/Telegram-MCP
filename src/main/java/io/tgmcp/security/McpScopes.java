package io.tgmcp.security;

import java.util.Set;

/**
 * Scopes an MCP access token can carry.
 *
 * <p>The provider scope is what binds a token to "Claude" or "ChatGPT". It is granted to a
 * registered OAuth client and appears in the validated token, so the provider a request is
 * for is always derived from authentication and never from a tool argument.
 */
public final class McpScopes {

    public static final String READ = "mcp:read";

    public static final String PROVIDER_PREFIX = "provider:";
    public static final String ANTHROPIC_CLAUDE = "ANTHROPIC_CLAUDE";
    public static final String OPENAI_CHATGPT = "OPENAI_CHATGPT";

    public static final String SCOPE_ANTHROPIC_CLAUDE = PROVIDER_PREFIX + "anthropic_claude";
    public static final String SCOPE_OPENAI_CHATGPT = PROVIDER_PREFIX + "openai_chatgpt";

    public static final Set<String> KNOWN_PROVIDERS = Set.of(ANTHROPIC_CLAUDE, OPENAI_CHATGPT);

    private McpScopes() {
    }

    /** The provider a set of granted scopes authorizes, or null when none does. */
    public static String providerFor(Set<String> scopes) {
        if (scopes.contains(SCOPE_ANTHROPIC_CLAUDE)) {
            return ANTHROPIC_CLAUDE;
        }
        if (scopes.contains(SCOPE_OPENAI_CHATGPT)) {
            return OPENAI_CHATGPT;
        }
        return null;
    }

    public static String scopeFor(String provider) {
        return PROVIDER_PREFIX + provider.toLowerCase(java.util.Locale.ROOT);
    }
}
