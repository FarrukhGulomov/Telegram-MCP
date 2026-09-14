package io.tgmcp.support;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.tgmcp.security.McpActorResolver;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * Mints access tokens with the same key the authorization server signs with, so tests drive
 * the real resource-server validation path (signature, issuer, audience, expiry, scopes)
 * rather than stubbing authentication out.
 */
@Component
public class TestTokens {

    private final JwtEncoder encoder;
    private final String keyId;

    public TestTokens(JWKSource<SecurityContext> jwkSource) throws Exception {
        this.encoder = new NimbusJwtEncoder(jwkSource);
        List<JWK> keys = jwkSource.get(new com.nimbusds.jose.jwk.JWKSelector(
                new com.nimbusds.jose.jwk.JWKMatcher.Builder().build()), null);
        this.keyId = keys.get(0).getKeyID();
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
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(userId == null ? "anonymous" : userId.toString())
                .audience(List.of(audience))
                .issuedAt(issuedAt)
                .expiresAt(now.plusSeconds(ttlSeconds))
                .claim("scope", String.join(" ", scopes))
                .claim("client_id", "test-client");
        if (tenantId != null) {
            claims.claim(McpActorResolver.CLAIM_TENANT, tenantId.toString());
        }
        if (userId != null) {
            claims.claim(McpActorResolver.CLAIM_USER, userId.toString());
        }
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(this.keyId).build();
        return this.encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
