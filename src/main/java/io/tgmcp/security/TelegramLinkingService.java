package io.tgmcp.security;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

import org.springframework.stereotype.Service;

/**
 * "Open the bot and press Start": the replacement for the oauth.telegram.org widget. A visitor
 * is shown a {@code t.me/<bot>?start=<token>} deep link; opening it and sending {@code /start
 * <token>} to the bot reaches {@code TelegramBotWebhookController}, which calls {@link
 * #completeLink} with whoever sent it. The browser side ({@code RegistrationController}) polls
 * {@link #check} for that same token to notice when it happens.
 */
@Service
public class TelegramLinkingService {

    private static final Duration TOKEN_TTL = Duration.ofMinutes(15);
    private static final int TOKEN_BYTES = 24;

    private final TelegramLinkTokenRepository tokens;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public TelegramLinkingService(TelegramLinkTokenRepository tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    public String createLinkToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        this.random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        this.tokens.insert(new TelegramLinkToken(token, null, null, null,
                this.clock.instant().plus(TOKEN_TTL), this.clock.instant()));
        return token;
    }

    /** Called by the webhook when a {@code /start <token>} update arrives. */
    public boolean completeLink(String token, long telegramUserId, String telegramUsername) {
        return this.tokens.claim(token, telegramUserId, telegramUsername, this.clock.instant());
    }

    /** @return the linked identity, once {@link #completeLink} has succeeded for this token. */
    public Optional<TelegramLinkToken> check(String token) {
        return this.tokens.findByToken(token).filter(TelegramLinkToken::isLinked);
    }
}
