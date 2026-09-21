package io.tgmcp.security;

import java.util.List;
import java.util.Map;

import io.tgmcp.config.AppProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Points the bot's webhook at this deployment on every startup -- so "open the bot and press
 * Start" ({@code TelegramLinkingService}) actually reaches {@code TelegramBotWebhookController}
 * without an operator running a one-off {@code setWebhook} call by hand. Best-effort: a failure
 * here (network, an invalid bot token) is logged, not fatal -- the rest of the application does
 * not depend on it, and "Sign in with Telegram" simply stays unusable until it is fixed.
 */
@Component
public class TelegramWebhookRegistrar implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TelegramWebhookRegistrar.class);

    private final AppProperties properties;
    private final TelegramBotApiCaller client;
    private final TelegramWebhookSecret secret;
    private final String publicBaseUrl;

    public TelegramWebhookRegistrar(AppProperties properties, TelegramBotApiCaller client,
                                    TelegramWebhookSecret secret,
                                    @Value("${tgmcp.oauth.public-base-url:http://localhost:8080}")
                                    String publicBaseUrl) {
        this.properties = properties;
        this.client = client;
        this.secret = secret;
        this.publicBaseUrl = publicBaseUrl;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!this.properties.telegramLogin().enabled()) {
            return;
        }
        try {
            this.client.call("setWebhook", Map.of(
                    "url", this.publicBaseUrl + "/telegram/bot-webhook",
                    "secret_token", this.secret.value(),
                    "allowed_updates", List.of("message"),
                    "drop_pending_updates", true));
            log.info("Registered the Telegram bot webhook against {}/telegram/bot-webhook", this.publicBaseUrl);
        }
        catch (TelegramBotMessageException ex) {
            log.warn("Could not register the Telegram bot webhook; \"Sign in with Telegram\" will not work "
                    + "until this succeeds: {}", ex.getMessage());
        }
    }
}
