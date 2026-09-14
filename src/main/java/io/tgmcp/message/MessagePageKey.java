package io.tgmcp.message;

import java.time.Instant;
import java.util.UUID;

/**
 * Keyset pagination position. Stable under concurrent inserts, unlike an offset.
 */
public record MessagePageKey(Instant sentAt, UUID chatId, long tgMessageId) {
}
