package io.tgmcp.security;

import java.util.List;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.tgmcp.config.AppProperties;

import org.springframework.boot.web.server.servlet.CookieSameSiteSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Two more filter chains: one for the MCP endpoint (bearer tokens, no session, no CSRF
 * because there is no cookie to forge), and one for the management UI (session cookie,
 * CSRF on, form login).
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

    /** Management UI: form login, session cookie, CSRF protection, conservative headers. */
    @Bean
    @Order(3)
    public SecurityFilterChain managementUiFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(requests -> requests
                        .requestMatchers("/login", "/css/**", "/js/**", "/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/", true).permitAll())
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

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** Session cookies are SameSite=Lax; {@code server.servlet.session.cookie.*} sets the rest. */
    @Bean
    public CookieSameSiteSupplier sessionCookieSameSiteSupplier() {
        return CookieSameSiteSupplier.ofLax().whenHasName("TGMCPSESSION");
    }
}
