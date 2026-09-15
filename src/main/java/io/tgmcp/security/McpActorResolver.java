package io.tgmcp.security;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import io.tgmcp.tenancy.ActorType;
import io.tgmcp.tenancy.AppUser;
import io.tgmcp.tenancy.AppUserRepository;
import io.tgmcp.tenancy.AuthenticatedActor;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Turns a validated access token into the identity policy decisions are made against.
 *
 * <p>Tenant and user come from signed claims only. There is deliberately no code path that
 * lets a tool argument, header or query parameter influence them.
 */
@Component
public class McpActorResolver {

    public static final String CLAIM_TENANT = "tenant_id";
    public static final String CLAIM_USER = "user_id";

    private final AppUserRepository users;

    public McpActorResolver(AppUserRepository users) {
        this.users = users;
    }

    public AuthenticatedActor resolve(Jwt jwt) {
        UUID tenantId = uuidClaim(jwt, CLAIM_TENANT);
        UUID userId = uuidClaim(jwt, CLAIM_USER);
        if (tenantId == null || userId == null) {
            throw new IllegalStateException("Access token is missing tenant/user binding");
        }
        // Security-audit finding SEC-09: a bearer token's claims describe the user as of the
        // moment it was issued, and can stay valid for its whole lifetime after that. Without
        // this, an account disabled -- or moved to a different tenant -- after a token was
        // minted would keep working through every MCP call until the token itself expired.
        // Re-checked fresh from the database on every call, exactly like CurrentActor does for
        // the management UI's session-based path.
        AppUser user = this.users.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Access token names an account that no longer exists"));
        if (!user.enabled()) {
            throw new IllegalStateException("Access token names a disabled account");
        }
        if (!user.tenantId().equals(tenantId)) {
            throw new IllegalStateException("Access token's tenant binding no longer matches the account");
        }
        return new AuthenticatedActor(tenantId, userId, jwt.getSubject(), ActorType.MCP_CLIENT,
                scopes(jwt), jwt.getClaimAsString("client_id"));
    }

    private static Set<String> scopes(Jwt jwt) {
        Set<String> scopes = new LinkedHashSet<>();
        Object claim = jwt.getClaim("scope");
        if (claim instanceof String raw) {
            for (String scope : raw.split("\\s+")) {
                if (!scope.isBlank()) {
                    scopes.add(scope);
                }
            }
        }
        else if (claim instanceof java.util.Collection<?> collection) {
            collection.forEach(value -> scopes.add(String.valueOf(value)));
        }
        return Set.copyOf(scopes);
    }

    private static UUID uuidClaim(Jwt jwt, String name) {
        String raw = jwt.getClaimAsString(name);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
