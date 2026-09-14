package io.tgmcp.security;

import java.util.List;
import java.util.Locale;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects any token that was not issued for this MCP server.
 *
 * <p>The MCP specification requires a server to accept only tokens that name it in the
 * audience (RFC 8707 §2), which is what stops a token minted for a different resource from
 * being replayed here.
 */
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final String expectedAudience;

    public AudienceValidator(String expectedAudience) {
        this.expectedAudience = normalise(expectedAudience);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        List<String> audience = token.getAudience();
        if (audience != null && audience.stream().map(AudienceValidator::normalise)
                .anyMatch(this.expectedAudience::equals)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                "The access token was not issued for this resource", null));
    }

    /** RFC 8707 canonical form: lowercase scheme and host, no trailing slash, no fragment. */
    public static String normalise(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        int fragment = trimmed.indexOf('#');
        if (fragment >= 0) {
            trimmed = trimmed.substring(0, fragment);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int schemeEnd = trimmed.indexOf("://");
        if (schemeEnd < 0) {
            return trimmed;
        }
        int pathStart = trimmed.indexOf('/', schemeEnd + 3);
        String authorityPart = pathStart < 0 ? trimmed : trimmed.substring(0, pathStart);
        String pathPart = pathStart < 0 ? "" : trimmed.substring(pathStart);
        return authorityPart.toLowerCase(Locale.ROOT) + pathPart;
    }
}
