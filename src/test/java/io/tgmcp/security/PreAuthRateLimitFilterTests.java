package io.tgmcp.security;

import java.time.Duration;
import java.time.Instant;

import io.tgmcp.ratelimit.RateLimiter;
import io.tgmcp.support.MutableClock;
import org.junit.jupiter.api.Test;

import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security-audit finding SEC-10: login, OAuth token issuance and dynamic client registration
 * are all reachable before -- or entirely without -- application authentication, and
 * previously shared no budget distinct from an authenticated MCP caller's. Exercises the
 * filter directly (no Spring context, no real HTTP) against its own IP-keyed budget.
 */
class PreAuthRateLimitFilterTests {

    @Test
    void blocksOnceTheIpsBudgetIsSpentAndLeavesOtherPathsAlone() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-15T12:00:00Z"));
        RateLimiter limiter = new RateLimiter(2, Duration.ofMinutes(1), clock);
        PreAuthRateLimitFilter filter = new PreAuthRateLimitFilter("POST", "/login", limiter);

        assertThat(status(filter, "POST", "/login")).isEqualTo(200);
        assertThat(status(filter, "POST", "/login")).isEqualTo(200);
        assertThat(status(filter, "POST", "/login")).isEqualTo(429);

        // A different path is not guarded by this filter instance at all.
        assertThat(status(filter, "GET", "/login")).isEqualTo(200);
        assertThat(status(filter, "POST", "/other")).isEqualTo(200);
    }

    @Test
    void refillsOverTimeSoARealCallerIsNotPermanentlyLockedOut() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-15T12:00:00Z"));
        RateLimiter limiter = new RateLimiter(1, Duration.ofMinutes(1), clock);
        PreAuthRateLimitFilter filter = new PreAuthRateLimitFilter("POST", "/oauth2/token", limiter);

        assertThat(status(filter, "POST", "/oauth2/token")).isEqualTo(200);
        assertThat(status(filter, "POST", "/oauth2/token")).isEqualTo(429);

        clock.advance(Duration.ofMinutes(1));

        assertThat(status(filter, "POST", "/oauth2/token")).isEqualTo(200);
    }

    private static int status(PreAuthRateLimitFilter filter, String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }
}
