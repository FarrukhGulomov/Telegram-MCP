package io.tgmcp.security;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import com.nimbusds.jose.jwk.JWK;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.tgmcp.common.KeyMaterial;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;
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
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2AuthorizationCodeRequestAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

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
            log.warn("Rejecting self-registered client declaring a provider:* scope: {}", scopes);
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_client_metadata",
                    "Dynamic client registration cannot claim a provider identity (scopes starting with '"
                            + McpScopes.PROVIDER_PREFIX + "'); that identity is reserved to clients "
                            + "registered through a trusted, non-self-service path.", null));
        }
    }

    /**
     * Security-audit finding F18: RFC 8707 lets a client ask for a specific {@code resource}
     * at the authorize step. Spring Authorization Server 7.1 has no first-class support for
     * negotiating it (see docs/production-prerequisites.md), so a client naming a resource
     * other than this server's own was silently ignored rather than rejected -- the issued
     * token still carried this server's audience regardless of what was asked for, which is
     * not itself exploitable (this server only ever mints tokens for itself) but is a
     * standards-conformance gap a careful client is entitled to notice. Reject outright rather
     * than silently substitute.
     */
    private static void rejectMismatchedResourceParameter(OAuth2AuthorizationCodeRequestAuthenticationContext context,
                                                           String expectedResource) {
        OAuth2AuthorizationCodeRequestAuthenticationToken authentication = context.getAuthentication();
        Object requested = authentication.getAdditionalParameters().get("resource");
        if (requested == null) {
            return;
        }
        String normalisedRequested = AudienceValidator.normalise(String.valueOf(requested));
        if (!expectedResource.equals(normalisedRequested)) {
            throw new OAuth2AuthorizationCodeRequestAuthenticationException(
                    new OAuth2Error("invalid_target",
                            "This authorization server does not serve the requested resource.", null),
                    authentication);
        }
    }

    /**
     * Root cause of the Claude.ai connector's "Authorization ... failed", confirmed by the raw
     * request logging added below: Claude's {@code GET /oauth2/authorize} carries no
     * {@code scope} parameter at all. RFC 6749 6.3.3 treats an omitted scope as "whatever the
     * authorization server chooses to default it to", but Spring Authorization Server applies
     * no such default -- {@code OAuth2AuthorizationCodeRequestAuthenticationProvider} uses the
     * request's scopes verbatim, so "no scope requested" becomes "zero scopes to authorize".
     * The (unmodified) default consent page renders exactly one checkbox per scope in that set,
     * so zero requested scopes means zero checkboxes: nothing the user could ever check, and a
     * submission with no {@code scope} value present is indistinguishable from an explicit
     * denial to {@code OAuth2AuthorizationConsentAuthenticationProvider}, which treats an empty
     * approved-scope set as denial -- a 100%-repeatable failure no matter what the user does on
     * the page. Wraps the library's own converter rather than replacing request handling
     * entirely: only the specific case of "no scope in the request" is touched, by substituting
     * the registered client's own scope (exactly what it declared at Dynamic Client
     * Registration time) so there is something real to render and consent to.
     *
     * <p>The second case this handles, confirmed from a real ChatGPT connection attempt: the
     * connector requests every scope this server's discovery metadata advertises at all --
     * {@code openid mcp:read provider:anthropic_claude provider:openai_chatgpt} -- rather than
     * only the one {@code provider:*} scope it is actually entitled to, because a generic
     * connector has no way to know in advance which one that is. Spring's own validator treats
     * "requested scope is not a subset of the registered client's scope" as a hard
     * {@code invalid_scope} rejection, which would make this connector (and any other equally
     * generic one) permanently unable to connect. Rather than reject, this restricts the
     * request to the intersection with what the client actually holds -- silently dropping
     * {@code provider:anthropic_claude} for a client that only has {@code
     * provider:openai_chatgpt} -- so the consent page, and the token that comes out of it, can
     * never carry a provider scope the client was not granted (finding F09 is enforced by what
     * the client is registered for, not by what it asked for).
     */
    private static AuthenticationConverter reconcileRequestedScopeWithRegisteredClientScope(
            RegisteredClientRepository registeredClientRepository) {
        AuthenticationConverter delegate = new OAuth2AuthorizationCodeRequestAuthenticationConverter();
        return request -> {
            Authentication authentication = delegate.convert(request);
            if (!(authentication instanceof OAuth2AuthorizationCodeRequestAuthenticationToken token)) {
                return authentication;
            }
            RegisteredClient registeredClient = registeredClientRepository.findByClientId(token.getClientId());
            if (registeredClient == null || registeredClient.getScopes().isEmpty()) {
                return authentication;
            }
            java.util.Set<String> requested = token.getScopes();
            if (requested == null || requested.isEmpty()) {
                log.info("Authorize request from client {} carried no scope; defaulting to its registered scope {}",
                        token.getClientId(), registeredClient.getScopes());
                return withScopes(token, registeredClient.getScopes());
            }
            if (registeredClient.getScopes().containsAll(requested)) {
                return authentication;
            }
            java.util.Set<String> allowed = new java.util.LinkedHashSet<>(requested);
            allowed.retainAll(registeredClient.getScopes());
            if (allowed.isEmpty()) {
                log.info("Authorize request from client {} requested {}, none of which it is registered for; "
                                + "defaulting to its registered scope {}",
                        token.getClientId(), requested, registeredClient.getScopes());
                return withScopes(token, registeredClient.getScopes());
            }
            log.info("Authorize request from client {} requested {} but is only registered for {}; restricting "
                            + "the granted scope to {}",
                    token.getClientId(), requested, registeredClient.getScopes(), allowed);
            return withScopes(token, allowed);
        };
    }

    private static OAuth2AuthorizationCodeRequestAuthenticationToken withScopes(
            OAuth2AuthorizationCodeRequestAuthenticationToken token, java.util.Set<String> scopes) {
        return new OAuth2AuthorizationCodeRequestAuthenticationToken(token.getAuthorizationUri(),
                token.getClientId(), (Authentication) token.getPrincipal(), token.getRedirectUri(),
                token.getState(), scopes, token.getAdditionalParameters());
    }

    /**
     * Temporary diagnostic: a real MCP client's authorize attempt was failing with no
     * indication of why -- the default behaviour here is a silent redirect back to the
     * client carrying an {@code error} query parameter, with nothing logged server-side, so
     * every rejection (mismatched redirect_uri, PKCE, scope, the resource-parameter check
     * above, or anything else the library's own default validator rejects) looked identical
     * to reading Railway's HTTP access log: a bare 302 with no way to tell which case, or
     * even that it was an error redirect rather than a successful one. Logs the exact
     * OAuth2Error before reproducing the same redirect-with-error behaviour the library's own
     * (not independently reusable) default handler would have performed.
     */
    private static void logAndRedirectAuthorizationError(HttpServletRequest request, HttpServletResponse response,
                                                          AuthenticationException exception) throws IOException {
        if (!(exception instanceof OAuth2AuthorizationCodeRequestAuthenticationException ex)) {
            log.warn("OAuth2 authorize request rejected: {}", exception.toString());
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "OAuth2 authorization request failed");
            return;
        }
        OAuth2Error error = ex.getError();
        OAuth2AuthorizationCodeRequestAuthenticationToken authRequest = ex.getAuthorizationCodeRequestAuthentication();
        log.warn("OAuth2 authorize request rejected: error={} description={} clientId={} redirectUri={}",
                error.getErrorCode(), error.getDescription(),
                authRequest == null ? null : authRequest.getClientId(),
                authRequest == null ? null : authRequest.getRedirectUri());
        String redirectUri = authRequest == null ? null : authRequest.getRedirectUri();
        if (redirectUri == null) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "OAuth2 authorization request failed: "
                    + error.getErrorCode());
            return;
        }
        StringBuilder location = new StringBuilder(redirectUri)
                .append(redirectUri.contains("?") ? '&' : '?')
                .append("error=").append(URLEncoder.encode(error.getErrorCode(), StandardCharsets.UTF_8));
        if (StringUtils.hasText(error.getDescription())) {
            location.append("&error_description=")
                    .append(URLEncoder.encode(error.getDescription(), StandardCharsets.UTF_8));
        }
        if (StringUtils.hasText(authRequest.getState())) {
            location.append("&state=").append(URLEncoder.encode(authRequest.getState(), StandardCharsets.UTF_8));
        }
        response.sendRedirect(location.toString());
    }

    /**
     * Temporary diagnostic: {@link #logAndRedirectAuthorizationError} showed a real client's
     * consent submission being rejected as {@code access_denied} with an empty approved-scope
     * set, on every attempt, regardless of what the user did on the consent page -- but that
     * only reports Spring's own parsed interpretation of the request, after the fact. Spring's
     * default consent page (see {@code DefaultConsentPage}) renders one {@code <input
     * name="scope" value="...">} checkbox per scope in the authorize request; if that request
     * carried no (or a mismatched) {@code scope} parameter, there is nothing to render a
     * checkbox for, nothing the user could ever check, and the submitted form is
     * indistinguishable from an explicit denial -- which would explain a 100%-repeatable
     * failure that survives "check the checkbox". This filter logs the exact bytes a real
     * client sent for {@code /oauth2/authorize} -- the raw query string on GET, the raw form
     * parameters on POST -- before anything in Spring's authorization/consent machinery gets a
     * chance to interpret them, so that hypothesis can be confirmed or ruled out directly.
     */
    private static final class RawOAuth2AuthorizeRequestLogger extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if ("/oauth2/authorize".equals(request.getRequestURI())) {
                if ("GET".equalsIgnoreCase(request.getMethod())) {
                    log.info("Raw GET /oauth2/authorize query string: {}", request.getQueryString());
                }
                else if ("POST".equalsIgnoreCase(request.getMethod())) {
                    java.util.Map<String, String> params = new java.util.LinkedHashMap<>();
                    request.getParameterMap().forEach((key, values) -> params.put(key,
                            java.util.Arrays.toString(values)));
                    log.info("Raw POST /oauth2/authorize form parameters: {}", params);
                }
            }
            chain.doFilter(request, response);
        }
    }

    /**
     * Temporary diagnostic: after correcting the connector URL to include {@code /mcp} (so the
     * client properly discovers this server via RFC 9728 protected-resource metadata instead of
     * probing the bare origin), Dynamic Client Registration itself started failing with a bare
     * 400, logged nowhere. The most likely explanation: {@code .well-known/oauth-protected-
     * resource} advertises {@code provider:anthropic_claude} as a supported scope for this
     * resource (see {@code WebSecurityConfig}) -- which a spec-following client has every reason
     * to request for itself once it reads that document -- while {@link
     * #rejectSelfRegisteredProviderScope} categorically refuses any self-registered client that
     * asks for a {@code provider:*} scope (finding F09). If that is what is happening, Claude's
     * own registration request would show it declaring exactly that scope. Logs the raw JSON
     * body of the registration request -- not just whatever Spring's client metadata parsing
     * makes of it -- before anything downstream reads it, then replays those exact bytes so
     * registration proceeds completely unaffected by having been observed.
     */
    private static final class RawOAuth2RegisterRequestLogger extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (!"/oauth2/register".equals(request.getRequestURI()) || !"POST".equalsIgnoreCase(request.getMethod())) {
                chain.doFilter(request, response);
                return;
            }
            byte[] body = org.springframework.util.StreamUtils.copyToByteArray(request.getInputStream());
            log.info("Raw POST /oauth2/register body: {}", new String(body, StandardCharsets.UTF_8));
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override
                public ServletInputStream getInputStream() {
                    return new ServletInputStream() {
                        private final java.io.ByteArrayInputStream replay = new java.io.ByteArrayInputStream(body);

                        @Override
                        public boolean isFinished() {
                            return replay.available() == 0;
                        }

                        @Override
                        public boolean isReady() {
                            return true;
                        }

                        @Override
                        public void setReadListener(ReadListener readListener) {
                        }

                        @Override
                        public int read() {
                            return replay.read();
                        }
                    };
                }
            }, response);
        }
    }

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerFilterChain(HttpSecurity http, AppProperties properties,
                                                               java.time.Clock clock,
                                                               RegisteredClientRepository registeredClientRepository)
            throws Exception {
        OAuth2AuthorizationServerConfigurer configurer = new OAuth2AuthorizationServerConfigurer();
        RequestMatcher endpoints = configurer.getEndpointsMatcher();
        String expectedResource = AudienceValidator.normalise(properties.mcp().resourceIdentifier());

        // Security-audit finding SEC-10: token issuance and dynamic client registration are
        // both reachable with no prior credential (registration by design; token issuance by
        // any client that can reach the endpoint), so each gets its own IP-keyed budget,
        // independent of the MCP tool-call budget that only ever applies afterward, to an
        // already-issued token.
        io.tgmcp.ratelimit.RateLimiter tokenLimiter = new io.tgmcp.ratelimit.RateLimiter(
                properties.abuseControls().oauthTokenRequestsPerMinutePerIp(), java.time.Duration.ofMinutes(1),
                clock);
        io.tgmcp.ratelimit.RateLimiter registrationLimiter = new io.tgmcp.ratelimit.RateLimiter(
                properties.abuseControls().oauthRegistrationsPerHourPerIp(), java.time.Duration.ofHours(1), clock);
        http.addFilterBefore(new PreAuthRateLimitFilter("POST", "/oauth2/token", tokenLimiter),
                org.springframework.security.web.context.SecurityContextHolderFilter.class);
        http.addFilterBefore(new PreAuthRateLimitFilter("POST", "/oauth2/register", registrationLimiter),
                org.springframework.security.web.context.SecurityContextHolderFilter.class);

        http.securityMatcher(endpoints)
                .addFilterBefore(new RawOAuth2AuthorizeRequestLogger(), SecurityContextHolderFilter.class)
                .addFilterBefore(new RawOAuth2RegisterRequestLogger(), SecurityContextHolderFilter.class)
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
                        //    through a trusted, non-self-service path (io.tgmcp.config
                        //    .TrustedMcpClientSeeder pre-registers "claude" and "chatgpt" this
                        //    way; the real connectors use that client id directly -- e.g.
                        //    Claude.ai's own "Add an OAuth Client ID" connector setting -- rather
                        //    than registering themselves here).
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
                        // Security-audit finding F18: rejects an authorize request naming a
                        // resource this server does not serve, per RFC 8707, rather than
                        // silently ignoring it. Wraps rather than replaces the endpoint's own
                        // full default validation (redirect_uri, scope, PKCE, grant type,
                        // prompt) -- the no-arg validator instance already composes all of
                        // those internally.
                        .authorizationEndpoint(authorization -> authorization
                                .authorizationRequestConverters(converters -> converters.replaceAll(converter ->
                                        converter instanceof OAuth2AuthorizationCodeRequestAuthenticationConverter
                                                ? reconcileRequestedScopeWithRegisteredClientScope(registeredClientRepository)
                                                : converter))
                                .authenticationProviders(providers -> providers.forEach(provider -> {
                                    if (provider instanceof OAuth2AuthorizationCodeRequestAuthenticationProvider p) {
                                        p.setAuthenticationValidator(
                                                new OAuth2AuthorizationCodeRequestAuthenticationValidator()
                                                        .andThen(context -> rejectMismatchedResourceParameter(
                                                                context, expectedResource)));
                                    }
                                }))
                                .errorResponseHandler(AuthorizationServerConfig::logAndRedirectAuthorizationError))
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
        return new TrustedProviderAugmentingClientRepository(new JdbcRegisteredClientRepository(jdbcTemplate));
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
            // Deliberately a plain ArrayList, not List.of(...): Spring Authorization Server
            // persists a JWT access token's claims (including this audience list) as part of
            // the stored OAuth2Authorization's metadata, JSON-serialized with polymorphic
            // type info by JdbcOAuth2AuthorizationService. Confirmed from a real Railway
            // deployment's logs: List.of(...)'s runtime type (java.util.ImmutableCollections
            // $List12) is a JDK-internal class Spring Security's Jackson PolymorphicTypeValidator
            // does not allow-list, so every read of that row after the first (a token refresh,
            // a second grant) threw InvalidTypeIdException and fell through to a bare redirect
            // -- silent from the client's point of view, but fatal to ever completing a second
            // request. An ordinary ArrayList is a type every such allow-list already covers.
            claims.audience(new java.util.ArrayList<>(java.util.List.of(properties.mcp().resourceIdentifier())));
            claims.claim("client_id", context.getRegisteredClient().getClientId());

            String principalName = context.getPrincipal().getName();
            users.findByPhoneNumber(principalName).ifPresent(user -> bindUser(claims, user));
        };
    }

    private void bindUser(JwtClaimsSet.Builder claims, AppUser user) {
        claims.claim(McpActorResolver.CLAIM_TENANT, user.tenantId().toString());
        claims.claim(McpActorResolver.CLAIM_USER, user.id().toString());
    }

    /**
     * Signing key for issued tokens, plus any retiring keys kept around for verification only.
     *
     * <p>Security-audit finding F18: an ephemeral key used to be generated unconditionally.
     * That is still the fallback so local development needs no secret, but it invalidates
     * every outstanding token on restart and gives each replica of a multi-instance
     * deployment its own, mutually incompatible key -- neither of which is acceptable once
     * {@code tgmcp.secrets.oauth-rsa-private-key} is configured with a real, shared key.
     *
     * <p>Security-audit finding SEC-17: that single key was also the *only* one ever
     * considered valid, so rotating it invalidated every still-live token the instant the new
     * key took over -- "recording a key ID is not sufficient when no old-key lookup/rekey
     * protocol exists". {@code tgmcp.secrets.retiring-oauth-verification-keys} adds previous
     * keys' public halves to this same set for verification, giving rotation an actual overlap
     * window; a verification-only entry (no private key) can never be selected to sign a new
     * token, so this cannot accidentally un-rotate signing.
     */
    @Bean
    public JWKSource<SecurityContext> jwkSource(
            @Value("${tgmcp.oauth.rsa-key-id:tgmcp-dev}") String keyId,
            AppProperties properties) {
        String configuredKey = properties.secrets().oauthRsaPrivateKey();
        RSAKey currentKey = KeyMaterial.isConfigured(configuredKey)
                ? loadPersistentRsaKey(configuredKey, keyId)
                : generateEphemeralRsaKey(keyId);
        List<JWK> keys = new ArrayList<>();
        keys.add(currentKey);
        for (String entry : properties.secrets().retiringOauthVerificationKeys()) {
            RSAKey retiring = loadRetiringVerificationKey(entry);
            if (retiring.getKeyID().equals(currentKey.getKeyID())) {
                throw new IllegalStateException("tgmcp.secrets.retiring-oauth-verification-keys names key id '"
                        + retiring.getKeyID() + "', which is also the current signing key id (tgmcp.oauth.rsa-key-id"
                        + "). Retiring entries must use the key id they were originally issued under.");
            }
            keys.add(retiring);
        }
        return new ImmutableJWKSet<>(new JWKSet(keys));
    }

    /**
     * Parses one {@code keyId:base64X509PublicKey} entry into a verification-only {@link
     * RSAKey} -- deliberately built with no private key, so Spring Authorization Server's
     * signer (which requires one) can never pick it to sign a new token.
     */
    static RSAKey loadRetiringVerificationKey(String entry) {
        int separator = entry.indexOf(':');
        if (separator <= 0 || separator == entry.length() - 1) {
            throw new IllegalStateException("tgmcp.secrets.retiring-oauth-verification-keys entry '" + entry
                    + "' is not in 'keyId:base64X509PublicKey' form");
        }
        String retiringKeyId = entry.substring(0, separator).trim();
        String base64PublicKey = entry.substring(separator + 1).trim();
        try {
            byte[] der = Base64.getDecoder().decode(base64PublicKey);
            KeyFactory factory = KeyFactory.getInstance("RSA");
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(der));
            return new RSAKey.Builder(publicKey).keyID(retiringKeyId).build();
        }
        catch (RuntimeException | java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("tgmcp.secrets.retiring-oauth-verification-keys entry for key id '"
                    + retiringKeyId + "' could not be loaded as a base64-encoded X.509 RSA public key", ex);
        }
    }

    RSAKey loadPersistentRsaKey(String base64Pkcs8PrivateKey, String keyId) {
        try {
            byte[] der = Base64.getDecoder().decode(base64Pkcs8PrivateKey.trim());
            KeyFactory factory = KeyFactory.getInstance("RSA");
            RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) factory.generatePrivate(new PKCS8EncodedKeySpec(der));
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            log.info("Loaded a persistent RSA signing key (id '{}') for the authorization server.", keyId);
            return new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(keyId).build();
        }
        catch (RuntimeException | java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("tgmcp.secrets.oauth-rsa-private-key is set but could not be "
                    + "loaded as a base64-encoded PKCS8 RSA private key", ex);
        }
    }

    RSAKey generateEphemeralRsaKey(String keyId) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            log.warn("Generated an ephemeral RSA signing key for the local authorization server. Tokens do "
                    + "not survive a restart, and a second replica would use a different key. Configure "
                    + "tgmcp.secrets.oauth-rsa-private-key before production.");
            return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID(keyId)
                    .build();
        }
        catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("RSA unavailable", ex);
        }
    }

}
