package io.tgmcp.telegram.gateway;

public record RemoteChat(long tgChatId,
                         String title,
                         String chatType,
                         String username,
                         Integer memberCount) {
}
