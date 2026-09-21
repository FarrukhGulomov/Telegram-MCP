package io.tgmcp.security;

/**
 * Sends a plain text message through the Telegram Bot -- used by {@link LoginTwoFactorService}
 * to deliver a login code, and by {@code TelegramBotWebhookController} to confirm a successful
 * "open the bot and press Start" link. A thin interface so tests can substitute a fake rather
 * than reach the real Bot API.
 */
public interface TelegramBotMessenger {

    /**
     * @param chatId a Telegram user id. The Bot API only accepts this as a private-chat
     *               destination once that user has opened a chat with the bot at least once
     *               (typically by pressing Start); until then delivery fails and {@link
     *               TelegramBotMessageException} is thrown.
     */
    void sendMessage(long chatId, String text) throws TelegramBotMessageException;
}
