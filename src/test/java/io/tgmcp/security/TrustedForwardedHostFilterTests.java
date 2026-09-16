package io.tgmcp.security;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security-audit finding SEC-13: with {@code server.forward-headers-strategy=framework}, Spring
 * trusts {@code X-Forwarded-Host} unconditionally and rewrites {@link
 * jakarta.servlet.http.HttpServletRequest#getServerName()} to match -- by the time this filter
 * runs (immediately after Spring's own {@code ForwardedHeaderFilter}), that rewrite has already
 * happened, so it exercises the filter against the request's effective host directly rather than
 * a raw header.
 */
class TrustedForwardedHostFilterTests {

    @Test
    void isANoOpWhenNoTrustedHostsAreConfigured() throws Exception {
        TrustedForwardedHostFilter filter = new TrustedForwardedHostFilter(List.of());

        assertThat(status(filter, "attacker-controlled.invalid", "/login")).isEqualTo(200);
    }

    @Test
    void rejectsAnEffectiveHostNotInTheConfiguredList() throws Exception {
        TrustedForwardedHostFilter filter = new TrustedForwardedHostFilter(List.of("mcp.example.com"));

        assertThat(status(filter, "attacker-controlled.invalid", "/login")).isEqualTo(400);
    }

    @Test
    void allowsAnEffectiveHostThatIsInTheConfiguredList() throws Exception {
        TrustedForwardedHostFilter filter = new TrustedForwardedHostFilter(List.of("mcp.example.com"));

        assertThat(status(filter, "mcp.example.com", "/login")).isEqualTo(200);
    }

    @Test
    void matchingIsCaseInsensitive() throws Exception {
        TrustedForwardedHostFilter filter = new TrustedForwardedHostFilter(List.of("mcp.example.com"));

        assertThat(status(filter, "MCP.EXAMPLE.COM", "/login")).isEqualTo(200);
    }

    @Test
    void alwaysAllowsHealthChecksRegardlessOfHost() throws Exception {
        // Platforms frequently probe /actuator/health over an internal address distinct from
        // the public hostname this filter otherwise enforces (security-audit finding SEC-13).
        TrustedForwardedHostFilter filter = new TrustedForwardedHostFilter(List.of("mcp.example.com"));

        assertThat(status(filter, "10.0.0.5", "/actuator/health")).isEqualTo(200);
    }

    private static int status(TrustedForwardedHostFilter filter, String effectiveHost, String path)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        request.setServerName(effectiveHost);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }
}
