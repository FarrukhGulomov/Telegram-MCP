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

/** One thin HTTP wrapper over the Telegram Bot API, shared by whichever call site needs it. */
@Component
public class TelegramBotApiClient implements TelegramBotApiCaller {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotApiClient.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public TelegramBotApiClient(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
    }

    /**
     * @throws TelegramBotMessageException on any transport failure or a {@code "ok": false}
     *         response. The bot token never appears in the exception message or in a log line.
     */
    @Override
    public JsonNode call(String method, Map<String, Object> params) throws TelegramBotMessageException {
        String botToken = this.properties.telegramLogin().botToken();
        String body = this.objectMapper.writeValueAsString(params);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.telegram.org/bot" + botToken + "/" + method))
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
            throw new TelegramBotMessageException("Interrupted while calling the Telegram Bot API", ex);
        }
        JsonNode json;
        try {
            json = this.objectMapper.readTree(response.body());
        }
        catch (RuntimeException ex) {
            throw new TelegramBotMessageException("Telegram Bot API returned an unparseable response", ex);
        }
        if (response.statusCode() != 200 || !json.path("ok").asBoolean(false)) {
            String description = json.path("description").asString("(no description)");
            log.warn("Telegram Bot API {} failed: status={} description={}", method, response.statusCode(),
                    description);
            throw new TelegramBotMessageException(description);
        }
        return json.path("result");
    }
}
