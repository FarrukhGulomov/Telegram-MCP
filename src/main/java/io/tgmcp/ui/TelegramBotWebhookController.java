package io.tgmcp.ui;

import io.tgmcp.security.TelegramBotMessageException;
import io.tgmcp.security.TelegramBotMessenger;
import io.tgmcp.security.TelegramLinkingService;
import io.tgmcp.security.TelegramWebhookSecret;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives Telegram Bot API updates ({@code TelegramWebhookRegistrar} points the bot's webhook
 * here on every startup). The only update this cares about is {@code /start <token>} sent to
 * the bot by a visitor who followed the deep link {@code RegistrationController} showed them --
 * that finishes {@code TelegramLinkingService#completeLink} for whichever browser is polling for
 * that same token. Every other update is ignored.
 *
 * <p>Never CSRF-protected (Telegram's servers carry no session cookie or CSRF token to present)
 * -- see {@code WebSecurityConfig}'s dedicated, stateless filter chain for this path. Requests
 * are authenticated only by {@link TelegramWebhookSecret}, which Telegram echoes back on every
 * delivery as the {@code X-Telegram-Bot-Api-Secret-Token} header set at {@code setWebhook} time.
 */
@RestController
public class TelegramBotWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotWebhookController.class);
    private static final String START_COMMAND = "/start ";

    private final TelegramWebhookSecret secret;
    private final TelegramLinkingService linkingService;
    private final TelegramBotMessenger messenger;
    private final ObjectMapper objectMapper;

    public TelegramBotWebhookController(TelegramWebhookSecret secret, TelegramLinkingService linkingService,
                                        TelegramBotMessenger messenger, ObjectMapper objectMapper) {
        this.secret = secret;
        this.linkingService = linkingService;
        this.messenger = messenger;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/telegram/bot-webhook")
    public ResponseEntity<Void> receive(
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String presentedSecret,
            @RequestBody String rawBody) {
        if (!this.secret.value().equals(presentedSecret)) {
            log.warn("Rejected a /telegram/bot-webhook request: missing or incorrect secret token.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        JsonNode update;
        try {
            update = this.objectMapper.readTree(rawBody);
        }
        catch (RuntimeException ex) {
            log.warn("Rejected a /telegram/bot-webhook request: unparseable body.");
            return ResponseEntity.ok().build();
        }
        handleStartCommand(update.path("message"));
        // Telegram expects a fast 2xx regardless of what the update contained; anything else
        // makes it retry the same update, which would only ever repeat the same no-op.
        return ResponseEntity.ok().build();
    }

    private void handleStartCommand(JsonNode message) {
        String text = message.path("text").asString("");
        if (!text.startsWith(START_COMMAND)) {
            return;
        }
        String token = text.substring(START_COMMAND.length()).strip();
        long telegramUserId = message.path("from").path("id").asLong(0);
        if (token.isEmpty() || telegramUserId == 0) {
            return;
        }
        String username = message.path("from").path("username").asString(null);
        boolean linked = this.linkingService.completeLink(token, telegramUserId, username);
        if (linked) {
            try {
                this.messenger.sendMessage(telegramUserId,
                        "Telegram Consent MCP: linked. Return to your browser to continue.");
            }
            catch (TelegramBotMessageException ex) {
                // Purely a courtesy message; the link itself already succeeded.
                log.warn("Linked Telegram user {} but could not send the confirmation message: {}",
                        telegramUserId, ex.getMessage());
            }
        }
    }
}
