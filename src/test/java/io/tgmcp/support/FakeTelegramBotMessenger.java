package io.tgmcp.support;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.tgmcp.security.TelegramBotMessageException;
import io.tgmcp.security.TelegramBotMessenger;

/**
 * Records every message a test's login flow would have sent through the real Bot API, instead
 * of ever making that HTTP call -- and lets a test simulate delivery failure (the account has
 * never opened a chat with the bot) for one chat id.
 */
public class FakeTelegramBotMessenger implements TelegramBotMessenger {

    private static final Pattern CODE_PATTERN = Pattern.compile("\\b(\\d{6})\\b");

    public record SentMessage(long chatId, String text) {
    }

    private final List<SentMessage> sent = new CopyOnWriteArrayList<>();
    private final Set<Long> blockedChatIds = ConcurrentHashMap.newKeySet();

    @Override
    public void sendMessage(long chatId, String text) throws TelegramBotMessageException {
        if (this.blockedChatIds.contains(chatId)) {
            throw new TelegramBotMessageException("Forbidden: bot can't initiate conversation with a user");
        }
        this.sent.add(new SentMessage(chatId, text));
    }

    public List<SentMessage> sent() {
        return List.copyOf(this.sent);
    }

    /** The 6-digit code from the most recent message sent to {@code chatId}. */
    public String latestCodeFor(long chatId) {
        for (int i = this.sent.size() - 1; i >= 0; i--) {
            SentMessage message = this.sent.get(i);
            if (message.chatId() == chatId) {
                Matcher matcher = CODE_PATTERN.matcher(message.text());
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }
        throw new IllegalStateException("No code was ever sent to chat " + chatId);
    }

    public void blockChat(long chatId) {
        this.blockedChatIds.add(chatId);
    }

    public void reset() {
        this.sent.clear();
        this.blockedChatIds.clear();
    }
}
