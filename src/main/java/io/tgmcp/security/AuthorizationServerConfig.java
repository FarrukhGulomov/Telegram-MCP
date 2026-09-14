package io.tgmcp.security;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

import javax.sql.DataSource;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.tgmcp.config.AppProperties;
import io.tgmcp.tenancy.AppUser;
import io.tgmcp.tenancy.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * A standards-compliant OAuth 2.1 authorization server, provided by Spring Authorization
 * Server. Nothing about the protocol or the token cryptography is hand-rolled here — this
 * class only wires the library and adds the tenant/user binding this application needs.
 *
 * <p>Dynamic client registration (RFC 7591) is enabled so that remote MCP clients such as
 * Claude and ChatGPT can register themselves.
 */
@Configuration(proxyBeanMethods = false)
public class AuthorizationServerConfig {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationServerConfig.class);

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer configurer = new OAuth2AuthorizationServerConfigurer();
        RequestMatcher endpoints = configurer.getEndpointsMatcher();

        http.securityMatcher(endpoints)
                .with(configurer, server -> server
                        // RFC 7591: remote MCP clients register themselves.
                        .clientRegistrationEndpoint(Customizer.withDefaults())
                        .oidc(Customizer.withDefaults()))
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                // The authorization server's own endpoints are OAuth endpoints, not browser
                // forms; the library handles their protection.
                .csrf(csrf -> csrf.ignoringRequestMatchers(endpoints))
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/login"),
                                new org.springframework.security.web.util.matcher.MediaTypeRequestMatcher(
                                        org.springframework.http.MediaType.TEXT_HTML)));
        return http.build();
    }

    @Bean
    public RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcRegisteredClientRepository(jdbcTemplate);
    }

    @Bean
    public OAuth2AuthorizationService authorizationService(JdbcTemplate jdbcTemplate,
                                                           RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(jdbcTemplate, clients);
    }

    @Bean
    public OAuth2AuthorizationConsentService authorizationConsentService(JdbcTemplate jdbcTemplate,
                                                                         RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcTemplate, clients);
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings(
            @Value("${tgmcp.oauth.issuer:http://localhost:8080}") String issuer) {
        return AuthorizationServerSettings.builder().issuer(issuer).build();
    }

    /**
     * Binds each access token to the tenant, the user and this resource.
     *
     * <p>The audience is what makes the token usable only here; the tenant and user claims
     * are what let the MCP layer derive identity without trusting tool arguments.
     */
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> jwtCustomizer(AppUserRepository users,
                                                                   AppProperties properties) {
        return context -> {
            if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                return;
            }
            JwtClaimsSet.Builder claims = context.getClaims();
            claims.audience(java.util.List.of(properties.mcp().resourceIdentifier()));
            claims.claim("client_id", context.getRegisteredClient().getClientId());

            String principalName = context.getPrincipal().getName();
            users.findByEmail(principalName).ifPresent(user -> bindUser(claims, user));
        };
    }

    private void bindUser(JwtClaimsSet.Builder claims, AppUser user) {
        claims.claim(McpActorResolver.CLAIM_TENANT, user.tenantId().toString());
        claims.claim(McpActorResolver.CLAIM_USER, user.id().toString());
    }

    /**
     * Signing key for issued tokens.
     *
     * <p>An ephemeral key is generated when none is configured, so local development needs
     * no secret; every restart then invalidates previously issued tokens, which is exactly
     * what you want in development and never what you want in production.
     */
    @Bean
    public JWKSource<SecurityContext> jwkSource(
            @Value("${tgmcp.oauth.rsa-key-id:tgmcp-dev}") String keyId) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID(keyId)
                    .build();
            log.warn("Generated an ephemeral RSA signing key for the local authorization server. "
                    + "Tokens do not survive a restart. Configure a persistent key before production.");
            return new ImmutableJWKSet<>(new JWKSet(rsaKey));
        }
        catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("RSA unavailable", ex);
        }
    }

}
