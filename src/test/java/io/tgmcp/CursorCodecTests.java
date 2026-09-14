package io.tgmcp;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.tgmcp.config.AppProperties;
import io.tgmcp.mcp.CursorCodec;
import io.tgmcp.mcp.CursorException;
import io.tgmcp.mcp.CursorPayload;
import io.tgmcp.message.MessagePageKey;
import io.tgmcp.support.MutableClock;
import io.tgmcp.tenancy.ActorType;
import io.tgmcp.tenancy.AuthenticatedActor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cursors are the one piece of server state a client holds, so tampering with them must not
 * widen access. These tests pin every binding the codec claims to enforce.
 */
class CursorCodecTests {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");
    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-01T00:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final CursorCodec codec = newCodec("8vEG46MjjWduNxNzDzMGNfR8s8cPE0RbWFGeLliwERA=");

    private final UUID tenantA = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID userA = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID chatA = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final AuthenticatedActor actor = actor(this.tenantA, this.userA);
    private final MessagePageKey key = new MessagePageKey(Instant.parse("2026-03-03T10:00:00Z"),
            this.chatA, 4242L);

    private CursorCodec newCodec(String base64Key) {
        AppProperties properties = new AppProperties(
                new AppProperties.Telegram(io.tgmcp.telegram.gateway.TelegramAdapterKind.MOCK,
                        null, null, "./tdlib", false),
                mcpDefaults(),
                new AppProperties.Consent("2026-09-v1", true, Duration.ofDays(365)),
                syncDefaults(),
                new AppProperties.Secrets(null, "test", base64Key),
                new AppProperties.Demo(false, "demo", "Demo", "a@b.invalid", "pw", List.of()));
        return new CursorCodec(properties, this.clock);
    }

    private static AppProperties.Mcp mcpDefaults() {
        return new AppProperties.Mcp(50, 200, 20, 512, 20, 262144, 4000, 366,
                Duration.ofMinutes(15), 120, "http://localhost:8080/mcp", "mcp:read");
    }

    private static AppProperties.Sync syncDefaults() {
        return new AppProperties.Sync(Duration.ofSeconds(30), 200, 5, 2, 6,
                Duration.ofSeconds(5), Duration.ofMinutes(10), Duration.ofHours(1));
    }

    private static AuthenticatedActor actor(UUID tenantId, UUID userId) {
        return new AuthenticatedActor(tenantId, userId, "sub", ActorType.MCP_CLIENT,
                Set.of("mcp:read"), "client");
    }

    private String encode() {
        return this.codec.encode("search_messages", this.actor, scope(), query(), FROM, TO, this.key);
    }

    private String scope() {
        return CursorCodec.scopeHash(List.of(this.chatA));
    }

    private String query() {
        return CursorCodec.queryHash("backpressure");
    }

    private CursorPayload decode(String cursor) {
        return this.codec.decode(cursor, "search_messages", this.actor, scope(), query(), FROM, TO);
    }

    @Test
    void roundTripsTheKeysetPosition() {
        CursorPayload payload = decode(encode());

        assertThat(payload.keyChatId()).isEqualTo(this.chatA);
        assertThat(payload.keyMessageId()).isEqualTo(4242L);
        assertThat(payload.keySentAt()).isEqualTo(Instant.parse("2026-03-03T10:00:00Z"));
        assertThat(this.codec.toPageKey(payload)).isEqualTo(this.key);
    }

    @Test
    void rejectsAnEditedPayloadBecauseTheSignatureNoLongerVerifies() {
        String cursor = encode();
        String payloadPart = cursor.substring(0, cursor.indexOf('.'));
        String signaturePart = cursor.substring(cursor.indexOf('.') + 1);

        // Re-encode the payload with a different tenant, keeping the original signature.
        String decoded = new String(java.util.Base64.getUrlDecoder().decode(payloadPart),
                java.nio.charset.StandardCharsets.UTF_8);
        String tampered = decoded.replace(this.tenantA.toString(),
                "99999999-9999-9999-9999-999999999999");
        String forged = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tampered.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "." + signaturePart;

        assertThatThrownBy(() -> decode(forged))
                .isInstanceOf(CursorException.class)
                .hasMessageContaining("signature");
    }

    @Test
    void rejectsACursorSignedWithADifferentKey() {
        CursorCodec other = newCodec("yICZfaQOU0NUn2QY+IQMQOZtXk5K/AqPfq3UwIoBgZU=");
        String foreign = other.encode("search_messages", this.actor, scope(), query(), FROM, TO, this.key);

        assertThatThrownBy(() -> decode(foreign)).isInstanceOf(CursorException.class);
    }

    @Test
    void rejectsAnotherUsersCursorEvenWithinTheSameTenant() {
        String cursor = encode();
        AuthenticatedActor otherUser = actor(this.tenantA, UUID.randomUUID());

        assertThatThrownBy(() -> this.codec.decode(cursor, "search_messages", otherUser, scope(),
                query(), FROM, TO))
                .isInstanceOf(CursorException.class)
                .hasMessageContaining("does not belong");
    }

    @Test
    void rejectsReuseAgainstADifferentTool() {
        String cursor = encode();

        assertThatThrownBy(() -> this.codec.decode(cursor, "get_chat_history", this.actor, scope(),
                query(), FROM, TO))
                .isInstanceOf(CursorException.class);
    }

    @Test
    void rejectsReuseAgainstADifferentChatScope() {
        String cursor = encode();
        String widerScope = CursorCodec.scopeHash(List.of(this.chatA, UUID.randomUUID()));

        assertThatThrownBy(() -> this.codec.decode(cursor, "search_messages", this.actor, widerScope,
                query(), FROM, TO))
                .isInstanceOf(CursorException.class);
    }

    @Test
    void rejectsReuseAgainstADifferentQueryOrRange() {
        String cursor = encode();

        assertThatThrownBy(() -> this.codec.decode(cursor, "search_messages", this.actor, scope(),
                CursorCodec.queryHash("something else"), FROM, TO))
                .isInstanceOf(CursorException.class);
        assertThatThrownBy(() -> this.codec.decode(cursor, "search_messages", this.actor, scope(),
                query(), FROM, TO.plus(Duration.ofDays(1))))
                .isInstanceOf(CursorException.class);
    }

    @Test
    void expiresAfterTheConfiguredTtl() {
        String cursor = encode();
        this.clock.advance(Duration.ofMinutes(14));
        assertThat(decode(cursor)).isNotNull();

        this.clock.advance(Duration.ofMinutes(2));
        assertThatThrownBy(() -> decode(cursor))
                .isInstanceOf(CursorException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void rejectsGarbage() {
        for (String bad : List.of("", "not-a-cursor", "abc.def", ".", "a.")) {
            assertThatThrownBy(() -> decode(bad)).isInstanceOf(CursorException.class);
        }
    }

    @Test
    void scopeHashIgnoresOrdering() {
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();

        assertThat(CursorCodec.scopeHash(List.of(one, two)))
                .isEqualTo(CursorCodec.scopeHash(List.of(two, one)));
    }
}
