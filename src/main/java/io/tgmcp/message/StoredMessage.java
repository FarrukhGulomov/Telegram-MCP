package io.tgmcp.message;

import java.time.Instant;
import java.util.UUID;

/**
 * A stored Telegram message.
 *
 * <p>{@code content} is untrusted third-party text. Nothing in this application interprets
 * it as an instruction, and the UI never renders it as markup.
 *
 * @param mediaKind {@code "PHOTO"} or {@code "VIDEO_THUMBNAIL"} when a visual preview can be
 *                  fetched on demand for this message, {@code null} otherwise -- see {@link
 *                  io.tgmcp.telegram.gateway.RemoteMessage#mediaKind()}.
 * @param mediaRemoteFileId the durable reference {@code TelegramGateway#downloadMessageMedia}
 *                          needs to fetch it, or {@code null} when {@code mediaKind} is
 *                          {@code null}. The bytes themselves are never stored here.
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
                            String sourceLink,
                            String mediaKind,
                            String mediaRemoteFileId) {

    public boolean deleted() {
        return this.deletedAt != null;
    }
}
