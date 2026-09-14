package io.tgmcp.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.SecretKey;

import io.tgmcp.common.Hashing;
import io.tgmcp.common.KeyMaterial;
import io.tgmcp.config.AppProperties;
import io.tgmcp.message.MessagePageKey;
import io.tgmcp.tenancy.AuthenticatedActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * Encodes and verifies opaque pagination cursors.
 *
 * <p>A cursor is {@code base64url(payload) + "." + base64url(HMAC-SHA256(payload))}. The MAC
 * uses a server-side key held outside the database; the payload itself is not secret, but it
 * is authenticated, so a client that edits any field — including the tenant or the keyset
 * position — gets a verification failure rather than a different page.
 */
@Component
public class CursorCodec {

    private static final Logger log = LoggerFactory.getLogger(CursorCodec.class);
    private static final String VERSION = "1";
    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKey key;
    private final Clock clock;
    private final java.time.Duration ttl;

    public CursorCodec(AppProperties properties, Clock clock) {
        this.key = KeyMaterial.load(properties.secrets().cursorSigningKey(), ALGORITHM, "cursor-signing");
        this.clock = clock;
        this.ttl = properties.mcp().cursorTtl();
        if (!KeyMaterial.isConfigured(properties.secrets().cursorSigningKey())) {
            log.warn("TGMCP_CURSOR_SIGNING_KEY is not set; using a derived development key.");
        }
    }

    /** Stable hash over the chat scope of a request, order-independent. */
    public static String scopeHash(List<UUID> chatIds) {
        return Hashing.sha256Hex(chatIds.stream().map(UUID::toString).sorted().toList().toString())
                .substring(0, 32);
    }

    public static String queryHash(String query) {
        return Hashing.sha256Hex(query == null ? "" : query).substring(0, 32);
    }

    public String encode(String tool, AuthenticatedActor actor, String scopeHash, String queryHash,
                         Instant rangeFrom, Instant rangeTo, MessagePageKey key) {
        Instant expiresAt = this.clock.instant().plus(this.ttl);
        String payload = String.join("|",
                VERSION,
                tool,
                actor.tenantId().toString(),
                actor.userId().toString(),
                scopeHash,
                queryHash,
                Long.toString(rangeFrom.toEpochMilli()),
                Long.toString(rangeTo.toEpochMilli()),
                Long.toString(key.sentAt().toEpochMilli()),
                key.chatId().toString(),
                Long.toString(key.tgMessageId()),
                Long.toString(expiresAt.toEpochMilli()));
        byte[] raw = payload.getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(raw) + "." + ENCODER.encodeToString(sign(raw));
    }

    /**
     * Verifies a cursor against the request that presented it.
     *
     * @throws CursorException if the signature, expiry, caller, tool, chat scope, query or
     *                         date range does not match
     */
    public CursorPayload decode(String cursor, String tool, AuthenticatedActor actor, String scopeHash,
                                String queryHash, Instant rangeFrom, Instant rangeTo) {
        if (cursor == null || cursor.isBlank()) {
            throw new CursorException("Cursor is empty");
        }
        int dot = cursor.indexOf('.');
        if (dot <= 0 || dot == cursor.length() - 1) {
            throw new CursorException("Malformed cursor");
        }
        byte[] raw;
        byte[] mac;
        try {
            raw = DECODER.decode(cursor.substring(0, dot));
            mac = DECODER.decode(cursor.substring(dot + 1));
        }
        catch (IllegalArgumentException ex) {
            throw new CursorException("Malformed cursor");
        }
        if (!MessageDigest.isEqual(mac, sign(raw))) {
            throw new CursorException("Cursor signature does not verify");
        }

        String[] parts = new String(raw, StandardCharsets.UTF_8).split("\\|", -1);
        if (parts.length != 12 || !VERSION.equals(parts[0])) {
            throw new CursorException("Unsupported cursor version");
        }
        CursorPayload payload;
        try {
            payload = new CursorPayload(parts[1], UUID.fromString(parts[2]), UUID.fromString(parts[3]),
                    parts[4], parts[5],
                    Instant.ofEpochMilli(Long.parseLong(parts[6])), Instant.ofEpochMilli(Long.parseLong(parts[7])),
                    Instant.ofEpochMilli(Long.parseLong(parts[8])), UUID.fromString(parts[9]),
                    Long.parseLong(parts[10]), Instant.ofEpochMilli(Long.parseLong(parts[11])));
        }
        catch (IllegalArgumentException ex) {
            throw new CursorException("Malformed cursor");
        }

        if (!this.clock.instant().isBefore(payload.expiresAt())) {
            throw new CursorException("Cursor has expired");
        }
        if (!payload.tool().equals(tool)
                || !payload.tenantId().equals(actor.tenantId())
                || !payload.userId().equals(actor.userId())
                || !payload.scopeHash().equals(scopeHash)
                || !payload.queryHash().equals(queryHash)
                || !payload.rangeFrom().equals(rangeFrom)
                || !payload.rangeTo().equals(rangeTo)) {
            throw new CursorException("Cursor does not belong to this request");
        }
        return payload;
    }

    public MessagePageKey toPageKey(CursorPayload payload) {
        return new MessagePageKey(payload.keySentAt(), payload.keyChatId(), payload.keyMessageId());
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(this.key);
            return mac.doFinal(payload);
        }
        catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to sign cursor", ex);
        }
    }
}
