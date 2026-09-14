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
        this.requiredScopes = properties.mcp().requiredScope() + " " + McpScopes.SCOPE_ANTHROPIC_CLAUDE;
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
