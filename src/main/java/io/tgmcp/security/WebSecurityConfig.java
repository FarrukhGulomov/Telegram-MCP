package io.tgmcp.security;

import java.util.List;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.tgmcp.config.AppProperties;

import org.springframework.boot.web.server.servlet.CookieSameSiteSupplier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Two more filter chains: one for the MCP endpoint (bearer tokens, no session, no CSRF
 * because there is no cookie to forge), and one for the management UI (session cookie,
 * CSRF on, no Spring Security-driven authentication -- {@code LoginController}/{@code
 * LoginVerificationController} establish the session directly via {@link
 * ManualSessionEstablisher} once a Telegram-delivered code has been proven, there being no
 * password for any {@code AuthenticationManager} to check).
 */
@Configuration(proxyBeanMethods = false)
public class WebSecurityConfig {

    /**
     * MCP endpoint and its discovery documents.
     *
     * <p>Stateless on purpose: every request carries its own validated token, so there is no
     * server-side session for an attacker to ride and no need for CSRF defences here.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain mcpFilterChain(HttpSecurity http,
                                              AppProperties properties,
                                              McpBearerAuthenticationEntryPoint entryPoint,
                                              @org.springframework.beans.factory.annotation.Value(
                                                      "${tgmcp.oauth.issuer:http://localhost:8080}")
                                              String issuer) throws Exception {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthorityPrefix("SCOPE_");
        authorities.setAuthoritiesClaimName("scope");
        converter.setJwtGrantedAuthoritiesConverter(authorities);

        http.securityMatcher("/mcp/**", "/mcp", "/.well-known/oauth-protected-resource/**",
                        "/.well-known/oauth-protected-resource")
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/.well-known/oauth-protected-resource",
                                "/.well-known/oauth-protected-resource/**").permitAll()
                        .anyRequest().hasAuthority("SCOPE_" + properties.mcp().requiredScope()))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(entryPoint)
                        // RFC 9728 Protected Resource Metadata, which the MCP specification
                        // requires. Served by Spring Security itself rather than hand-rolled;
                        // we only fill in the values specific to this deployment.
                        .protectedResourceMetadata(metadata -> metadata
                                .protectedResourceMetadataCustomizer(builder -> builder
                                        .resource(AudienceValidator.normalise(
                                                properties.mcp().resourceIdentifier()))
                                        .resourceName("Telegram Consent MCP")
                                        .authorizationServer(issuer)
                                        .scope(properties.mcp().requiredScope())
                                        .scope(McpScopes.SCOPE_ANTHROPIC_CLAUDE)
                                        .scope(McpScopes.SCOPE_OPENAI_CHATGPT)))
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(org.springframework.security.config.annotation.web.configurers.CsrfConfigurer::disable)
                .headers(headers -> headers
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));
        return http.build();
    }

    /**
     * The Telegram bot webhook ({@code TelegramBotWebhookController}): stateless, no CSRF (a
     * webhook delivery carries no session cookie or CSRF token to present), authenticated only
     * by {@link TelegramWebhookSecret} inside the controller itself -- same reasoning as {@link
     * #mcpFilterChain} for why this needs its own chain rather than living inside {@link
     * #managementUiFilterChain}'s session-and-CSRF-oriented one.
     */
    @Bean
    @Order(3)
    public SecurityFilterChain telegramWebhookFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/telegram/bot-webhook")
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(org.springframework.security.config.annotation.web.configurers.CsrfConfigurer::disable)
                .headers(headers -> headers
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));
        return http.build();
    }

    /** Management UI: session cookie, CSRF protection, conservative headers, no password auth. */
    @Bean
    @Order(4)
    public SecurityFilterChain managementUiFilterChain(HttpSecurity http, AppProperties properties,
                                                        java.time.Clock clock,
                                                        SecurityContextRepository securityContextRepository)
            throws Exception {
        // Security-audit finding SEC-10: a login attempt is unauthenticated by definition, so
        // it shares no budget with anything else in this application; IP-keyed and placed
        // before every other Spring Security filter so a caller who has spent it is turned
        // away before the credential check itself runs.
        io.tgmcp.ratelimit.RateLimiter loginLimiter = new io.tgmcp.ratelimit.RateLimiter(
                properties.abuseControls().loginAttemptsPerMinutePerIp(), java.time.Duration.ofMinutes(1), clock);
        http.addFilterBefore(new PreAuthRateLimitFilter("POST", "/login", loginLimiter),
                org.springframework.security.web.context.SecurityContextHolderFilter.class);
        // Self-service account creation (a workspace/email submission, then linking a Telegram
        // identity) is reachable without any prior authentication and creates a durable
        // tenant + user row per success -- its own, separately configured budget, same
        // reasoning and placement as loginLimiter above.
        io.tgmcp.ratelimit.RateLimiter registrationLimiter = new io.tgmcp.ratelimit.RateLimiter(
                properties.abuseControls().selfRegistrationsPerHourPerIp(), java.time.Duration.ofHours(1), clock);
        http.addFilterBefore(new PreAuthRateLimitFilter("POST", "/register", registrationLimiter),
                org.springframework.security.web.context.SecurityContextHolderFilter.class);
        // The second-factor code entry is exactly as guessable-by-brute-force as a password
        // would be if nothing budgeted attempts against it independently of the per-code
        // attempt cap LoginTwoFactorService already enforces -- IP-keyed, same budget and
        // reasoning as loginLimiter above.
        io.tgmcp.ratelimit.RateLimiter verifyLimiter = new io.tgmcp.ratelimit.RateLimiter(
                properties.abuseControls().loginAttemptsPerMinutePerIp(), java.time.Duration.ofMinutes(1), clock);
        http.addFilterBefore(new PreAuthRateLimitFilter("POST", "/login/verify", verifyLimiter),
                org.springframework.security.web.context.SecurityContextHolderFilter.class);
        http.securityContext(context -> context.securityContextRepository(securityContextRepository));
        http.authorizeHttpRequests(requests -> requests
                        .requestMatchers("/login", "/login/verify", "/login/verify/resend",
                                "/register", "/register/link-telegram",
                                "/register/link-telegram/status",
                                "/css/**", "/js/**", "/actuator/health").permitAll()
                        // Security-audit finding F17: AppUser carries an OWNER/ADMIN/MEMBER
                        // role, and DatabaseUserDetailsService already turns it into a granted
                        // ROLE_* authority -- but nothing here ever checked it, so any signed-in
                        // user of a tenant, regardless of role, could pause, revoke, delete data
                        // for, or reconfigure any chat in that tenant, or drive its Telegram
                        // connection. Every state-changing management action lives behind a POST
                        // under /chats/** or /connect/**; viewing (every GET) stays open to any
                        // signed-in tenant member, matching the app's existing same-tenant read
                        // model elsewhere.
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/chats/**", "/connect/**")
                        .hasAnyRole("OWNER", "ADMIN")
                        .anyRequest().authenticated())
                // No formLogin/httpBasic exists to install a default entry point any more (there
                // is no password for either to check), so an unauthenticated visitor to a
                // protected page must be sent to /login explicitly here instead.
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                        new org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint(
                                "/login")))
                .logout(logout -> logout.logoutSuccessUrl("/login?loggedOut").permitAll())
                .csrf(Customizer.withDefaults())
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; script-src 'self'; style-src 'self'; "
                                        + "img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; "
                                        + "form-action 'self'"))
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                        .frameOptions(frame -> frame.deny()));
        return http.build();
    }

    /**
     * Token validation for the MCP chain.
     *
     * <p>The delegate (built by Spring Authorization Server from the same JWK source it signs
     * with) verifies the signature and the default temporal claims. On top of that we require
     * the issuer to be ours and the audience to name this resource, because the MCP
     * specification requires a server to reject tokens minted for anything else.
     */
    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource,
                                 AppProperties properties,
                                 @org.springframework.beans.factory.annotation.Value(
                                         "${tgmcp.oauth.issuer:http://localhost:8080}") String issuer) {
        JwtDecoder delegate = OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(List.of(
                JwtValidators.createDefaultWithIssuer(issuer),
                new AudienceValidator(properties.mcp().resourceIdentifier())));
        return token -> {
            Jwt jwt = delegate.decode(token);
            OAuth2TokenValidatorResult result = validator.validate(jwt);
            if (result.hasErrors()) {
                OAuth2Error error = result.getErrors().iterator().next();
                throw new JwtValidationException("Access token rejected: " + error.getErrorCode(),
                        result.getErrors());
            }
            return jwt;
        };
    }

    /**
     * Shared with {@link ManualSessionEstablisher} (via {@code managementUiFilterChain}'s
     * explicit {@code .securityContext(...)} wiring below) so that a session established outside
     * the normal password check -- after a Telegram-delivered code, which has no password to
     * hand {@code AuthenticationManager} -- persists its {@link SecurityContext} exactly the way
     * this same filter chain reads it back on every later request.
     */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /** Session cookies are SameSite=Lax; {@code server.servlet.session.cookie.*} sets the rest. */
    @Bean
    public CookieSameSiteSupplier sessionCookieSameSiteSupplier() {
        return CookieSameSiteSupplier.ofLax().whenHasName("TGMCPSESSION");
    }

    /**
     * Security-audit finding SEC-13: registered directly with the servlet container (not tied
     * to either {@link SecurityFilterChain} above) so it covers every request regardless of
     * which chain would otherwise match it. Ordered immediately after Spring Boot's own {@code
     * ForwardedHeaderFilter}, which always runs at {@code Ordered.HIGHEST_PRECEDENCE} when
     * {@code server.forward-headers-strategy=framework} -- by the time this filter runs, {@link
     * jakarta.servlet.http.HttpServletRequest#getServerName()} already reflects whatever the
     * (possibly forged) forwarded headers said.
     */
    @Bean
    public FilterRegistrationBean<TrustedForwardedHostFilter> trustedForwardedHostFilter(
            AppProperties properties) {
        FilterRegistrationBean<TrustedForwardedHostFilter> registration = new FilterRegistrationBean<>(
                new TrustedForwardedHostFilter(properties.forwardedHeaders().trustedHosts()));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
