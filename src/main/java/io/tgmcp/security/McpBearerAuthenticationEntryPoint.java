package io.tgmcp.security;

import java.io.IOException;

import io.tgmcp.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Emits the RFC 9728 {@code WWW-Authenticate} challenge that MCP clients use to discover
 * where to authenticate, including the {@code scope} hint the spec recommends.
 *
 * <p>The body deliberately carries no detail about why the token was rejected.
 */
@Component
public class McpBearerAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final String resourceMetadataUrl;
    private final String requiredScopes;

    public McpBearerAuthenticationEntryPoint(AppProperties properties,
                                             @Value("${tgmcp.oauth.public-base-url:http://localhost:8080}")
                                             String publicBaseUrl) {
        this.resourceMetadataUrl = publicBaseUrl + "/.well-known/oauth-protected-resource";
        // Confirmed from a real ChatGPT connection attempt: this hint used to advertise only
        // provider:anthropic_claude, and ChatGPT's connector requests exactly what it says here
        // rather than knowing in advance which provider scope it should ask for -- so ChatGPT
        // ended up requesting Claude's scope, which AuthorizationServerConfig's own (deliberate,
        // documented) intersection logic correctly refused to grant to a client only registered
        // for provider:openai_chatgpt, leaving its token with no provider scope at all and every
        // tool call denied. Advertising every known provider scope here, exactly like the
        // discovery metadata already does, lets that same intersection logic narrow the grant
        // down to whichever one a given client actually holds, instead of this hint silently
        // picking a winner among providers that are mutually exclusive per client.
        this.requiredScopes = properties.mcp().requiredScope() + " " + McpScopes.SCOPE_ANTHROPIC_CLAUDE
                + " " + McpScopes.SCOPE_OPENAI_CHATGPT;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                "Bearer resource_metadata=\"" + this.resourceMetadataUrl + "\", "
                        + "scope=\"" + this.requiredScopes + "\"");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"unauthorized\"}");
    }
}
