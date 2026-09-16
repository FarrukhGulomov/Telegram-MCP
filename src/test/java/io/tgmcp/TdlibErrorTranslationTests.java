package io.tgmcp;

import java.time.Duration;

import io.tgmcp.telegram.gateway.TelegramAuthException;
import io.tgmcp.telegram.gateway.TelegramFloodWaitException;
import io.tgmcp.telegram.gateway.TelegramGatewayException;
import io.tgmcp.telegram.gateway.TelegramPermissionException;
import io.tgmcp.telegram.gateway.TelegramTransientException;
import io.tgmcp.telegram.tdlib.TdlibErrors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a Telegram failure is retryable decides whether the sync loop waits or gives up,
 * so the mapping is asserted directly rather than left implicit.
 */
class TdlibErrorTranslationTests {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private TelegramGatewayException translate(int code, String message) {
        return TdlibErrors.translate(this.mapper.readTree(
                "{\"@type\":\"error\",\"code\":" + code + ",\"message\":\"" + message + "\"}"));
    }

    @Test
    void recognisesAnErrorObject() {
        assertThat(TdlibErrors.isError(this.mapper.readTree("{\"@type\":\"error\",\"code\":400}"))).isTrue();
        assertThat(TdlibErrors.isError(this.mapper.readTree("{\"@type\":\"ok\"}"))).isFalse();
    }

    @Test
    void parsesTheWaitFromAFloodWaitMessage() {
        TelegramGatewayException ex = translate(429, "Too Many Requests: FLOOD_WAIT_37");

        assertThat(ex).isInstanceOf(TelegramFloodWaitException.class);
        assertThat(((TelegramFloodWaitException) ex).retryAfter()).isEqualTo(Duration.ofSeconds(37));
        assertThat(ex.retryable()).isTrue();
    }

    @Test
    void parsesTheAlternativeRetryAfterWording() {
        TelegramGatewayException ex = translate(429, "Too Many Requests: retry after 12");

        assertThat(((TelegramFloodWaitException) ex).retryAfter()).isEqualTo(Duration.ofSeconds(12));
    }

    @Test
    void fallsBackToAConservativeWaitForAn429WithNoHint() {
        TelegramGatewayException ex = translate(429, "Too Many Requests");

        assertThat(ex).isInstanceOf(TelegramFloodWaitException.class);
        assertThat(((TelegramFloodWaitException) ex).retryAfter()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void treatsAuthenticationFailuresAsNonRetryable() {
        for (String message : new String[] {"UNAUTHORIZED", "AUTH_KEY_UNREGISTERED", "SESSION_REVOKED"}) {
            TelegramGatewayException ex = translate(401, message);
            assertThat(ex).isInstanceOf(TelegramAuthException.class);
            assertThat(ex.retryable()).isFalse();
        }
    }

    @Test
    void treatsPermissionFailuresAsNonRetryable() {
        TelegramGatewayException ex = translate(403, "CHANNEL_PRIVATE");

        assertThat(ex).isInstanceOf(TelegramPermissionException.class);
        assertThat(ex.retryable()).isFalse();
    }

    @Test
    void treatsABadRequestAsNonRetryableBecauseRepeatingItCannotHelp() {
        assertThat(translate(400, "CHAT_ID_INVALID").retryable()).isFalse();
    }

    @Test
    void treatsChatNotFoundAsRetryableRatherThanAPermanentPermissionFailure() {
        // Real-account testing found getChat and getChatHistory answer exactly this message,
        // still code 400, for a chat this TDLib session has not yet loaded into its runtime
        // chat list -- true right after every process restart, even for a chat this app has
        // already synced before. Warming the list (TdlibTelegramGateway.warmChatList()) and
        // retrying succeeds, so this must never halt sync the way a truly malformed request does.
        TelegramGatewayException ex = translate(400, "Chat not found");

        assertThat(ex).isInstanceOf(TelegramTransientException.class);
        assertThat(ex.retryable()).isTrue();
    }

    @Test
    void treatsServerSideFailuresAsRetryable() {
        TelegramGatewayException ex = translate(500, "Internal Server Error");

        assertThat(ex).isInstanceOf(TelegramTransientException.class);
        assertThat(ex.retryable()).isTrue();
    }

    @Test
    void neverEchoesTheUpstreamMessageIntoTheExceptionText() {
        // The upstream message can carry identifiers; the translated exception keeps only a code.
        assertThat(translate(403, "CHAT_ADMIN_REQUIRED for chat -1001234567890").getMessage())
                .doesNotContain("1001234567890");
    }
}
