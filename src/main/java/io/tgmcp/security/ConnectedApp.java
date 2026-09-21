package io.tgmcp.security;

import java.time.Instant;

/** One MCP client (e.g. Claude, ChatGPT) currently holding an authorization for one account. */
public record ConnectedApp(String registeredClientId, String clientName, String scopes, Instant lastActiveAt) {
}
