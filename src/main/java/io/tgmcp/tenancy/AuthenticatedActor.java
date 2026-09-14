package io.tgmcp.tenancy;

import java.util.Set;
import java.util.UUID;

/**
 * The caller identity that every policy decision is made against.
 *
 * <p>Both fields are derived exclusively from validated authentication — a signed access
 * token for MCP callers, or the authenticated HTTP session for management UI users. They
 * are never read from tool arguments, request bodies or headers supplied by the caller.
 */
public record AuthenticatedActor(UUID tenantId,
                                 UUID userId,
                                 String subject,
                                 ActorType actorType,
                                 Set<String> scopes,
                                 String clientId) {

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }

    public String auditActorId() {
        return actorType == ActorType.MCP_CLIENT && clientId != null
                ? subject + "@" + clientId
                : subject;
    }
}
