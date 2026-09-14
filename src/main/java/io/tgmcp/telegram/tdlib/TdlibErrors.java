package io.tgmcp.telegram.tdlib;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.tgmcp.telegram.gateway.TelegramAuthException;
import io.tgmcp.telegram.gateway.TelegramFloodWaitException;
import io.tgmcp.telegram.gateway.TelegramGatewayException;
import io.tgmcp.telegram.gateway.TelegramPermissionException;
import io.tgmcp.telegram.gateway.TelegramTransientException;
import tools.jackson.databind.JsonNode;

/**
 * Maps TDLib {@code error} objects onto the gateway's retryable / non-retryable split.
 *
 * <p>Getting this split right is what stops the sync loop from hammering Telegram after a
 * permanent failure, and from giving up on a temporary one.
 */
public final class TdlibErrors {

    private static final Pattern FLOOD_WAIT = Pattern.compile("FLOOD_WAIT_(\\d+)");
    private static final Pattern RETRY_AFTER = Pattern.compile("retry after (\\d+)", Pattern.CASE_INSENSITIVE);

    private TdlibErrors() {
    }

    public static boolean isError(JsonNode node) {
        return node != null && node.has("@type") && "error".equals(node.path("@type").asString());
    }

    public static TelegramGatewayException translate(JsonNode error) {
        int code = error.path("code").asInt(0);
        String message = error.path("message").asString("");

        Duration floodWait = floodWait(message);
        if (floodWait != null) {
            return new TelegramFloodWaitException(floodWait);
        }
        if (code == 429) {
            // 429 without a parseable hint: back off for a conservative fixed period.
            return new TelegramFloodWaitException(Duration.ofSeconds(30));
        }
        if (code == 401 || message.contains("AUTH_KEY") || message.contains("SESSION_REVOKED")
                || message.contains("UNAUTHORIZED")) {
            return new TelegramAuthException("Telegram session is no longer valid (code " + code + ")");
        }
        if (code == 403 || message.contains("CHAT_ADMIN_REQUIRED") || message.contains("CHANNEL_PRIVATE")
                || message.contains("USER_BANNED")) {
            return new TelegramPermissionException("Chat is not readable by the connected account (code "
                    + code + ")");
        }
        if (code == 400) {
            // Bad request: retrying an identical malformed request cannot help.
            return new TelegramPermissionException("Telegram rejected the request (code 400)");
        }
        return new TelegramTransientException("Telegram error " + code);
    }

    static Duration floodWait(String message) {
        Matcher flood = FLOOD_WAIT.matcher(message);
        if (flood.find()) {
            return Duration.ofSeconds(Long.parseLong(flood.group(1)));
        }
        Matcher retry = RETRY_AFTER.matcher(message);
        if (retry.find()) {
            return Duration.ofSeconds(Long.parseLong(retry.group(1)));
        }
        return null;
    }
}
