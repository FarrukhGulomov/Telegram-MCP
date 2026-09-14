package io.tgmcp.mcp;

import java.time.Instant;
import java.util.UUID;

/**
 * What an opaque cursor actually binds.
 *
 * <p>Binding tenant, user, tool, chat scope and query means a cursor issued for one search
 * cannot be replayed against a different one, or by a different caller, to page past an
 * authorization boundary.
 */
public record CursorPayload(String tool,
                            UUID tenantId,
                            UUID userId,
                            String scopeHash,
                            String queryHash,
                            Instant rangeFrom,
                            Instant rangeTo,
                            Instant keySentAt,
                            UUID keyChatId,
                            long keyMessageId,
                            Instant expiresAt) {
}
