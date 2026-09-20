package io.tgmcp.security;

/** The Bot API rejected or failed to deliver a message -- most commonly because the recipient
 * has never opened a chat with the bot, so it has no destination to deliver into. */
public class TelegramBotMessageException extends Exception {

    public TelegramBotMessageException(String message) {
        super(message);
    }

    public TelegramBotMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
