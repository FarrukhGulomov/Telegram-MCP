package io.tgmcp.security;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import io.tgmcp.config.AppProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

/**
 * Real delivery for {@link TelegramBotMessenger}: one call to the Bot API's {@code sendMessage}
 * per code. Uses the same bot token {@code TelegramLoginController} already verifies widget
 * callbacks with (see {@link AppProperties.TelegramLogin}) -- one bot serves both purposes.
 */
@Component
public class HttpTelegramBotMessenger implements TelegramBotMessenger {

    private static final Logger log = LoggerFactory.getLogger(HttpTelegramBotMessenger.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public HttpTelegramBotMessenger(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
    }

    @Override
    public void sendMessage(long chatId, String text) throws TelegramBotMessageException {
        String botToken = this.properties.telegramLogin().botToken();
        String body = this.objectMapper.writeValueAsString(Map.of("chat_id", chatId, "text", text));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.telegram.org/bot" + botToken + "/sendMessage"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response;
        try {
            response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        }
        catch (IOException ex) {
            throw new TelegramBotMessageException("Could not reach the Telegram Bot API", ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TelegramBotMessageException("Interrupted while sending a Telegram Bot API message", ex);
        }
        JsonNode json;
        try {
            json = this.objectMapper.readTree(response.body());
        }
        catch (RuntimeException ex) {
            throw new TelegramBotMessageException("Telegram Bot API returned an unparseable response", ex);
        }
        if (response.statusCode() != 200 || !json.path("ok").asBoolean(false)) {
            // Never log the message text itself (it carries the one-time code); the
            // description alone is enough to diagnose "bot not started" versus other failures.
            String description = json.path("description").asString("(no description)");
            log.warn("Telegram Bot API sendMessage to chat {} failed: status={} description={}",
                    chatId, response.statusCode(), description);
            throw new TelegramBotMessageException(description);
        }
    }
}
