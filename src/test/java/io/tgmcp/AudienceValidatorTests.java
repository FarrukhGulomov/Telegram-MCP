package io.tgmcp;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.tgmcp.security.AudienceValidator;
import org.junit.jupiter.api.Test;

import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MCP specification requires a server to reject tokens that were not issued for it.
 */
class AudienceValidatorTests {

    private static final String RESOURCE = "https://mcp.example.com/mcp";

    private final AudienceValidator validator = new AudienceValidator(RESOURCE);

    private static Jwt tokenFor(String... audience) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("sub", "user")
                .audience(List.of(audience))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claims(claims -> claims.putAll(Map.of()))
                .build();
    }

    @Test
    void acceptsATokenNamingThisResource() {
        assertThat(this.validator.validate(tokenFor(RESOURCE)).hasErrors()).isFalse();
    }

    @Test
    void acceptsATokenNamingThisResourceAmongOthers() {
        assertThat(this.validator.validate(tokenFor("https://other.example.com", RESOURCE)).hasErrors())
                .isFalse();
    }

    @Test
    void rejectsATokenMintedForAnotherResource() {
        assertThat(this.validator.validate(tokenFor("https://other.example.com/mcp")).hasErrors()).isTrue();
    }

    @Test
    void rejectsATokenWithNoAudienceAtAll() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").claim("sub", "user").build();

        assertThat(this.validator.validate(jwt).hasErrors()).isTrue();
    }

    @Test
    void treatsRfc8707CanonicalVariantsAsEqual() {
        // Uppercase scheme/host and a trailing slash are the variants RFC 8707 says to tolerate.
        assertThat(this.validator.validate(tokenFor("HTTPS://MCP.EXAMPLE.COM/mcp")).hasErrors()).isFalse();
        assertThat(this.validator.validate(tokenFor(RESOURCE + "/")).hasErrors()).isFalse();
    }

    @Test
    void doesNotTreatADifferentPathAsEqual() {
        assertThat(this.validator.validate(tokenFor("https://mcp.example.com/other")).hasErrors()).isTrue();
        assertThat(this.validator.validate(tokenFor("https://mcp.example.com")).hasErrors()).isTrue();
    }

    @Test
    void stripsAFragmentBeforeComparing() {
        assertThat(AudienceValidator.normalise("https://mcp.example.com/mcp#frag"))
                .isEqualTo("https://mcp.example.com/mcp");
    }
}
