package io.tgmcp.support;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.tgmcp.security.McpActorResolver;

import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Component;

/**
 * Mints access tokens with the same key the authorization server signs with, so tests drive
 * the real resource-server validation path (signature, issuer, audience, expiry, scopes)
 * rather than stubbing authentication out.
 *
 * <p>Security-audit finding SEC-R05: the resource server now also checks a decoded token
 * against {@link OAuth2AuthorizationService#findByToken}, so it stops working the instant a
 * connected app is revoked (which deletes that row) rather than only once it naturally expires.
 * A hand-signed JWT with no backing {@code oauth2_authorization} row would fail that check
 * regardless of how valid its signature is, so every token minted here is now also persisted
 * through the same service the real {@code /oauth2/token} endpoint saves to -- against a
 * registered client this class ensures exists itself rather than relying on {@link
 * io.tgmcp.config.TrustedMcpClientSeeder}'s one-time startup registration, since {@code
 * AbstractIntegrationTest} truncates {@code oauth2_registered_client} before every test.
 */
@Component
public class TestTokens {

    private final JwtEncoder encoder;
    private final String keyId;
    private final RegisteredClientRepository registeredClients;
    private final OAuth2AuthorizationService authorizations;

    public TestTokens(JWKSource<SecurityContext> jwkSource, RegisteredClientRepository registeredClients,
                      OAuth2AuthorizationService authorizations) throws Exception {
        this.encoder = new NimbusJwtEncoder(jwkSource);
        List<JWK> keys = jwkSource.get(new com.nimbusds.jose.jwk.JWKSelector(
                new com.nimbusds.jose.jwk.JWKMatcher.Builder().build()), null);
        this.keyId = keys.get(0).getKeyID();
        this.registeredClients = registeredClients;
        this.authorizations = authorizations;
    }

    public String token(String issuer, String audience, UUID tenantId, UUID userId, List<String> scopes) {
        return token(issuer, audience, tenantId, userId, scopes, 300);
    }

    /** Issued ten minutes ago, expired five minutes ago. */
    public String expiredToken(String issuer, String audience, UUID tenantId, UUID userId,
                               List<String> scopes) {
        return token(issuer, audience, tenantId, userId, scopes, -300,
                Instant.now().minus(10, ChronoUnit.MINUTES));
    }

    public String token(String issuer, String audience, UUID tenantId, UUID userId,
                        List<String> scopes, long ttlSeconds) {
        return token(issuer, audience, tenantId, userId, scopes, ttlSeconds,
                Instant.now().minus(1, ChronoUnit.MINUTES));
    }

    public String token(String issuer, String audience, UUID tenantId, UUID userId,
                        List<String> scopes, long ttlSeconds, Instant issuedAt) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(ttlSeconds);
        String clientId = "claude";
        String principal = userId == null ? "anonymous" : userId.toString();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(principal)
                .audience(new ArrayList<>(List.of(audience)))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("scope", String.join(" ", scopes))
                .claim("client_id", clientId);
        if (tenantId != null) {
            claims.claim(McpActorResolver.CLAIM_TENANT, tenantId.toString());
        }
        if (userId != null) {
            claims.claim(McpActorResolver.CLAIM_USER, userId.toString());
        }
        JwtClaimsSet claimsSet = claims.build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(this.keyId).build();
        Jwt jwt = this.encoder.encode(JwtEncoderParameters.from(header, claimsSet));
        persistAuthorization(clientId, principal, jwt, issuedAt, expiresAt, scopes);
        return jwt.getTokenValue();
    }

    private void persistAuthorization(String clientId, String principal, Jwt jwt, Instant issuedAt,
                                      Instant expiresAt, List<String> scopes) {
        RegisteredClient client = ensureRegisteredClient(clientId);
        OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                jwt.getTokenValue(), issuedAt, expiresAt, Set.copyOf(scopes));
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .id(java.util.UUID.randomUUID().toString())
                .principalName(principal)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.copyOf(scopes))
                .token(accessToken, metadata -> metadata.put(OAuth2Authorization.Token.CLAIMS_METADATA_NAME,
                        new LinkedHashMap<String, Object>(jwt.getClaims())))
                .build();
        this.authorizations.save(authorization);
    }

    /**
     * Finds or lazily creates the trusted MCP client tokens are minted against. Not just a
     * lookup: {@code AbstractIntegrationTest} truncates {@code oauth2_registered_client} before
     * every test, so the row {@link io.tgmcp.config.TrustedMcpClientSeeder} inserts once at
     * application startup does not survive past the very first test in the suite.
     */
    private RegisteredClient ensureRegisteredClient(String clientId) {
        RegisteredClient existing = this.registeredClients.findByClientId(clientId);
        if (existing != null) {
            return existing;
        }
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientName(clientId)
                .clientAuthenticationMethod(org.springframework.security.oauth2.core.ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("http://localhost:8080/oauth2/callback")
                .scope(io.tgmcp.security.McpScopes.READ)
                .scope("openid")
                .scope(io.tgmcp.security.McpScopes.SCOPE_ANTHROPIC_CLAUDE)
                .scope(io.tgmcp.security.McpScopes.SCOPE_OPENAI_CHATGPT)
                .clientSettings(org.springframework.security.oauth2.server.authorization.settings.ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(true)
                        .build())
                .tokenSettings(org.springframework.security.oauth2.server.authorization.settings.TokenSettings.builder()
                        .accessTokenTimeToLive(java.time.Duration.ofMinutes(30))
                        .reuseRefreshTokens(false)
                        .build())
                .build();
        this.registeredClients.save(client);
        return client;
    }
}
