package io.tgmcp.chat;

import java.time.Instant;
import java.util.UUID;

public record Chat(UUID id,
                   UUID tenantId,
                   UUID connectionId,
                   long tgChatId,
                   String title,
                   String chatType,
                   String username,
                   Integer memberCount,
                   Instant discoveredAt) {
}
