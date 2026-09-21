package io.tgmcp.security;

import java.util.Map;

import org.springframework.stereotype.Component;

/** Real delivery for {@link TelegramBotMessenger}: one {@code sendMessage} call per code. */
@Component
public class HttpTelegramBotMessenger implements TelegramBotMessenger {

    private final TelegramBotApiCaller client;

    public HttpTelegramBotMessenger(TelegramBotApiCaller client) {
        this.client = client;
    }

    @Override
    public void sendMessage(long chatId, String text) throws TelegramBotMessageException {
        this.client.call("sendMessage", Map.of("chat_id", chatId, "text", text));
    }
}
