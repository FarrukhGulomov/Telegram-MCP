package io.tgmcp.telegram.gateway;

import java.util.UUID;

/**
 * Opaque handle for a Telegram client session. Business logic passes this around and
 * never sees phone numbers, codes, passwords, QR tokens or session keys.
 */
public record ConnectionRef(UUID tenantId, UUID connectionId) {
}
