package io.tgmcp.message;

import java.time.Instant;
import java.util.UUID;

/**
 * A stored Telegram message.
 *
 * <p>{@code content} is untrusted third-party text. Nothing in this application interprets
 * it as an instruction, and the UI never renders it as markup.
 */
public record StoredMessage(UUID id,
                            UUID tenantId,
                            UUID chatId,
                            long tgMessageId,
                            Long senderTgUserId,
                            String senderDisplayName,
                            Instant sentAt,
                            Instant editedAt,
                            Instant deletedAt,
                            String content,
                            Long replyToTgMessageId,
                            String sourceLink) {

    public boolean deleted() {
        return this.deletedAt != null;
    }
}
