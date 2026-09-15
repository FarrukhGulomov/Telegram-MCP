package io.tgmcp.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.tgmcp.ratelimit.RateLimiter;

import org.springframework.http.HttpStatus;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Security-audit finding SEC-10: applies an IP-keyed budget to one method+path pattern, for an
 * endpoint reachable before -- or entirely without -- application authentication (sign-in,
 * OAuth token issuance, dynamic client registration). Nothing previously limited these
 * independently of the MCP tool-call budget, which only ever applies to an already-
 * authenticated caller.
 *
 * <p>Registered as the very first filter in its security chain (before {@link
 * org.springframework.security.web.context.SecurityContextHolderFilter}), so a caller spending
 * its budget is turned away before any authentication attempt, token issuance, or client
 * registration is even parsed.
 *
 * <p>Client-IP trustworthiness depends on the same trusted-proxy boundary security-audit
 * finding SEC-13 addresses: this is a best-effort abuse control, not a cryptographic
 * guarantee, and is layered on top of -- never a replacement for -- network-level protections
 * at a real deployment's edge.
 */
public class PreAuthRateLimitFilter extends OncePerRequestFilter {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final String method;
    private final String pathPattern;
    private final RateLimiter limiter;

    public PreAuthRateLimitFilter(String method, String pathPattern, RateLimiter limiter) {
        this.method = method;
        this.pathPattern = pathPattern;
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (this.method.equalsIgnoreCase(request.getMethod())
                && MATCHER.match(this.pathPattern, request.getServletPath())
                && !this.limiter.tryAcquire(request.getRemoteAddr())) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("Too many requests. Try again shortly.");
            return;
        }
        chain.doFilter(request, response);
    }
}
