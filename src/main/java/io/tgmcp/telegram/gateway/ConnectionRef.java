package io.tgmcp.telegram.gateway;

import java.util.UUID;

/**
 * Opaque handle for a Telegram client session. Business logic passes this around and
 * never sees phone numbers, codes, passwords, QR tokens or session keys.
 *
 * @param routingKey Security-audit finding H1: which underlying native/session slot an
 *                    adapter should route this call to. Ordinarily equal to {@code tenantId} --
 *                    the compact constructor below defaults it that way, so every existing call
 *                    site naming just {@code (tenantId, connectionId)} keeps routing exactly as
 *                    it always did. A fresh login attempted for an <em>already-registered</em>
 *                    account is the one case that deliberately sets this to something else: a
 *                    freshly minted id distinct from {@code tenantId}, so an adapter that keys
 *                    its live session storage by routing key (see {@code
 *                    RoutedTdlibTelegramGateway}) opens an entirely separate, isolated session
 *                    for that attempt rather than reusing -- and thereby disturbing -- whichever
 *                    session already lives at the account's own {@code tenantId}. Only once that
 *                    new login is fully confirmed does {@link TelegramGateway#promoteSession}
 *                    move it over to become the live session at the real key.
 */
public record ConnectionRef(UUID tenantId, UUID connectionId, UUID routingKey) {

    public ConnectionRef(UUID tenantId, UUID connectionId) {
        this(tenantId, connectionId, tenantId);
    }
}
