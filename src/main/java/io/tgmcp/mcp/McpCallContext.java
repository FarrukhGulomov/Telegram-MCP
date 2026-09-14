package io.tgmcp.mcp;

import io.tgmcp.tenancy.AuthenticatedActor;

/** Per-call identity, derived from the validated access token. */
public record McpCallContext(AuthenticatedActor actor, String provider) {

    /** Key used to carry this through the MCP transport context. */
    public static final String TRANSPORT_KEY = "io.tgmcp.callContext";
}
