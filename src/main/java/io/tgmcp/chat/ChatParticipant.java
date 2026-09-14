package io.tgmcp.chat;

import java.time.Instant;
import java.util.UUID;

public record ChatParticipant(UUID id,
                              UUID tenantId,
                              UUID chatId,
                              long tgUserId,
                              String displayName,
                              boolean bot,
                              Instant firstSeenAt,
                              Instant leftAt) {

    public boolean active() {
        return this.leftAt == null;
    }
}
