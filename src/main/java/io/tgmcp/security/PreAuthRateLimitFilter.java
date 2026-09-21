package io.tgmcp.security;

import java.io.IOException;
import java.util.function.Function;

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
    private final Function<HttpServletRequest, String> keyExtractor;

    public PreAuthRateLimitFilter(String method, String pathPattern, RateLimiter limiter) {
        this(method, pathPattern, limiter, HttpServletRequest::getRemoteAddr);
    }

    /**
     * Security-audit finding SEC-R02: a caller-IP key alone lets an attacker who knows a
     * victim's phone number rotate source addresses (or simply wait out the per-IP window) to
     * keep disrupting that one number indefinitely. {@code keyExtractor} lets a second budget
     * be layered on the same path, keyed by whatever identifies the actual target instead --
     * returning {@code null} (e.g. the request carries no phone number at all) skips this
     * filter's check entirely rather than sharing a bucket across every such request.
     */
    public PreAuthRateLimitFilter(String method, String pathPattern, RateLimiter limiter,
                                  Function<HttpServletRequest, String> keyExtractor) {
        this.method = method;
        this.pathPattern = pathPattern;
        this.limiter = limiter;
        this.keyExtractor = keyExtractor;
    }

    /**
     * {@link OncePerRequestFilter}'s own dedup guard keys solely by {@code getClass().getName()}
     * -- fine for a single instance of a filter class appearing once in a chain, but this
     * application deliberately registers several distinct {@code PreAuthRateLimitFilter}
     * instances (different paths, different limiters, an IP-keyed and a phone-keyed budget on
     * the very same path). With the default name shared by every instance, the first one to run
     * for a given request sets the "already filtered" attribute for the whole class before
     * calling {@code chain.doFilter}; every later instance then sees that attribute still set
     * (it is only cleared after the chain call returns) and skips its own check entirely --
     * silently disabling every registered instance after the first, regardless of which path or
     * key it was actually configured for. Scoping the attribute to this instance restores each
     * one's independent behavior while still preventing any single instance from double-running
     * within one request (a forward/include).
     */
    @Override
    protected String getAlreadyFilteredAttributeName() {
        return getClass().getName() + ".FILTERED." + System.identityHashCode(this);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (this.method.equalsIgnoreCase(request.getMethod()) && MATCHER.match(this.pathPattern, servletRelativePath(request))) {
            String key = this.keyExtractor.apply(request);
            if (key != null && !this.limiter.tryAcquire(key)) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write("Too many requests. Try again shortly.");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * A second, previously-undiscovered bug found investigating a regression test for
     * SEC-R02: with the DispatcherServlet mapped at the default {@code "/"} (as this
     * application is, and as most Spring Boot apps are), the servlet container reports {@link
     * HttpServletRequest#getServletPath()} as an empty string for every request -- the whole
     * path lives in {@link HttpServletRequest#getRequestURI()} instead. Matching against {@code
     * getServletPath()} therefore compared every pattern here against {@code ""}, which never
     * matched anything: every {@code PreAuthRateLimitFilter} instance in the application (not
     * only the new phone-keyed one) has silently never enforced its budget on a real request.
     */
    private static String servletRelativePath(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.isEmpty() ? "/" : path;
    }
}
