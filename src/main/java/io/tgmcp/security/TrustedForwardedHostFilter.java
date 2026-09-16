package io.tgmcp.security;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Security-audit finding SEC-13: {@code server.forward-headers-strategy=framework} (see
 * application.yml) makes Spring's own {@code ForwardedHeaderFilter} trust {@code
 * X-Forwarded-Host}/{@code -Proto}/{@code -Port} unconditionally and rewrite {@link
 * HttpServletRequest#getServerName()} to match -- correct when a platform's edge always sits in
 * front of this process, but nothing enforced that assumption. If the application port were ever
 * reachable directly, or an intermediate hop passed those headers through unfiltered, a caller
 * could make this process believe it is reachable at a hostname it never was, which feeds
 * directly into every absolute redirect URL this app builds (see {@code
 * ForwardedHeaderRedirectTests}).
 *
 * <p>Registered with an order immediately after Spring's own {@code ForwardedHeaderFilter}
 * (which always runs at {@code Ordered.HIGHEST_PRECEDENCE}), so this sees the already-rewritten,
 * effective host -- the same value every downstream handler and redirect builder will use --
 * rather than trying to out-order or re-parse the raw header itself.
 *
 * <p>A no-op (every request passes through untouched) unless {@code
 * tgmcp.forwarded-headers.trusted-hosts} is explicitly configured; {@link
 * io.tgmcp.config.ProductionSafetyValidator} refuses to start with the unconditional-trust
 * default once real Telegram account access is authorized, exactly like every other setting that
 * is only "wrong for production" until that point.
 */
public class TrustedForwardedHostFilter extends OncePerRequestFilter {

    /** Health checks are frequently probed over an internal address distinct from the public
     * hostname this filter otherwise enforces; that check carries no redirect or cookie logic
     * worth protecting here. */
    private static final String EXEMPT_PATH_PREFIX = "/actuator/";

    private final Set<String> trustedHosts;

    public TrustedForwardedHostFilter(List<String> trustedHosts) {
        this.trustedHosts = trustedHosts.stream()
                .map(host -> host.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (this.trustedHosts.isEmpty() || request.getRequestURI().startsWith(EXEMPT_PATH_PREFIX)
                || this.trustedHosts.contains(request.getServerName().toLowerCase(Locale.ROOT))) {
            filterChain.doFilter(request, response);
            return;
        }
        response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unrecognized host");
    }
}
