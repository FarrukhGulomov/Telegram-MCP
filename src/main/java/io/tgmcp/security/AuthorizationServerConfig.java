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
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientRegistrationAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientRegistrationAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientRegistrationAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientRegistrationAuthenticationValidator;
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

    /**
     * Security-audit finding F09: rejects any Dynamic Client Registration request that
     * declares a {@code provider:*} scope for itself. Those scopes are what every
     * provider-gated MCP response ultimately trusts, so they must only ever be granted to a
     * client that was registered through a trusted path -- never one an anonymous caller
     * chose for itself over the open registration endpoint.
     */
    private static void rejectSelfRegisteredProviderScope(OAuth2ClientRegistrationAuthenticationContext context) {
        OAuth2ClientRegistrationAuthenticationToken authentication = context.getAuthentication();
        java.util.List<String> scopes = authentication.getClientRegistration().getScopes();
        if (scopes != null && scopes.stream().anyMatch(scope -> scope.startsWith(McpScopes.PROVIDER_PREFIX))) {
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_client_metadata",
                    "Dynamic client registration cannot claim a provider identity (scopes starting with '"
                            + McpScopes.PROVIDER_PREFIX + "'); that identity is reserved to clients "
                            + "registered through a trusted, non-self-service path.", null));
        }
    }

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer configurer = new OAuth2AuthorizationServerConfigurer();
        RequestMatcher endpoints = configurer.getEndpointsMatcher();

        http.securityMatcher(endpoints)
                .with(configurer, server -> server
                        // RFC 7591: remote MCP clients (Claude, ChatGPT) register themselves with
                        // no prior credential. Two defaults fight that:
                        //
                        // 1. openRegistrationAllowed defaults to false, so an anonymous request is
                        //    rejected with "invalid_token" (there is no initial access token to
                        //    present, by design, for open registration) — set it explicitly.
                        // 2. The default scope validator refuses ANY request that sets "scope" at
                        //    all ("scope must not be set during Dynamic Client Registration"),
                        //    which real MCP clients do (they declare the scope they intend to use).
                        //    We swap in Spring's own SIMPLE_SCOPE_VALIDATOR, a documented no-op
                        //    alternative, and accept the client's declared scope as its registered
                        //    scope -- but layered with rejectSelfRegisteredProviderScope() below
                        //    (security audit finding F09): SIMPLE_SCOPE_VALIDATOR by itself would
                        //    let ANY anonymous registrant declare a provider:* scope for itself,
                        //    which is exactly the identity chat_activations.provider_scope and
                        //    every provider-gated MCP response trust -- an unregistered impostor
                        //    client could self-register claiming to be "the real Claude" or "the
                        //    real ChatGPT" and, once a user completed the ordinary login-and-
                        //    consent flow at /oauth2/authorize, walk away with a token scoped to
                        //    read content that was only ever meant for the genuine provider. A
                        //    provider:* scope must only ever reach a client that was registered
                        //    through a trusted, non-self-service path (today: DemoDataSeeder).
                        //    mcp:read remains open to self-registration, since it grants nothing
                        //    beyond "this client may attempt MCP calls" -- chat-level access is
                        //    still gated entirely by the authenticated user's own tenant/chat data.
                        .clientRegistrationEndpoint(registration -> registration
                                .openRegistrationAllowed(true)
                                .authenticationProviders(providers -> providers.forEach(provider -> {
                                    if (provider instanceof OAuth2ClientRegistrationAuthenticationProvider p) {
                                        p.setAuthenticationValidator(
                                                OAuth2ClientRegistrationAuthenticationValidator.DEFAULT_REDIRECT_URI_VALIDATOR
                                                        .andThen(OAuth2ClientRegistrationAuthenticationValidator.DEFAULT_JWK_SET_URI_VALIDATOR)
                                                        .andThen(OAuth2ClientRegistrationAuthenticationValidator.SIMPLE_SCOPE_VALIDATOR)
                                                        .andThen(AuthorizationServerConfig::rejectSelfRegisteredProviderScope));
                                    }
                                })))
                        .oidc(Customizer.withDefaults()))
                .authorizeHttpRequests(requests -> requests
                        // Open (anonymous) dynamic client registration: a new MCP client has no
                        // credentials yet, so this is the one authorization-server endpoint that
                        // must not require a prior authenticated principal. RFC 7591 registration
                        // requests and the RFC 7592 read/update/delete-your-own-registration calls
                        // both live under this path; the registration access token they carry is
                        // validated by the endpoint's own filter, not by this authorization check.
                        .requestMatchers("/oauth2/register", "/oauth2/register/**").permitAll()
                        .anyRequest().authenticated())
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
