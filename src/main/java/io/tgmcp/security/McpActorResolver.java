package io.tgmcp.security;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import io.tgmcp.tenancy.ActorType;
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

    public AuthenticatedActor resolve(Jwt jwt) {
        UUID tenantId = uuidClaim(jwt, CLAIM_TENANT);
        UUID userId = uuidClaim(jwt, CLAIM_USER);
        if (tenantId == null || userId == null) {
            throw new IllegalStateException("Access token is missing tenant/user binding");
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
