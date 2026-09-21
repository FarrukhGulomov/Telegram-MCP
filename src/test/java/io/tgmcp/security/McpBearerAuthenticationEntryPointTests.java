package io.tgmcp.security;

import java.time.Duration;

import io.tgmcp.config.AppProperties;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirmed from a real ChatGPT connection attempt (ACCESS_DENIED on every tool call): this
 * challenge's {@code scope} hint used to advertise only {@code provider:anthropic_claude}, and
 * ChatGPT's connector requests exactly what this hint says rather than knowing in advance which
 * provider scope it holds. Its client is registered only for {@code provider:openai_chatgpt}, so
 * AuthorizationServerConfig's deliberate intersection logic correctly refused to grant the
 * (wrongly requested) Claude scope, leaving the token with no provider scope at all and every
 * tool call denied. This challenge must advertise every known provider scope, exactly like the
 * discovery metadata already does, so that intersection logic can narrow the grant down to
 * whichever provider a given client actually holds.
 */
class McpBearerAuthenticationEntryPointTests {

    private static final AppProperties.Mcp MCP_DEFAULTS = new AppProperties.Mcp(
            50, 200, 20, 512, 20, 262144, 4000, 366, Duration.ofMinutes(15), 120,
            "http://localhost:8080/mcp", "mcp:read");

    private static AppProperties properties() {
        return new AppProperties(null, MCP_DEFAULTS, null, null, null, null, null, null);
    }

    @Test
    void advertisesEveryKnownProviderScopeNotJustClaude() throws Exception {
        McpBearerAuthenticationEntryPoint entryPoint =
                new McpBearerAuthenticationEntryPoint(properties(), "https://app.example.invalid");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new BadCredentialsException("no token"));

        String challenge = response.getHeader(HttpHeaders.WWW_AUTHENTICATE);
        assertThat(challenge)
                .as("a client that blindly requests whatever this hint advertises must be able to "
                        + "request its own provider scope, not only Claude's")
                .contains("scope=\"mcp:read " + McpScopes.SCOPE_ANTHROPIC_CLAUDE + " "
                        + McpScopes.SCOPE_OPENAI_CHATGPT + "\"");
    }
}
