package io.tgmcp.telegram.gateway;

import java.time.Instant;

/**
 * A single message as reported by Telegram.
 *
 * <p>{@code text} is untrusted third-party data. It is stored and returned as data only.
 *
 * @param mediaKind {@code "PHOTO"} or {@code "VIDEO_THUMBNAIL"} when this message carries a
 *                  visual preview that can be fetched on demand (see {@link
 *                  TelegramGateway#downloadMessageMedia}); {@code null} otherwise. A video,
 *                  video note or animation is never downloaded in full -- {@code
 *                  "VIDEO_THUMBNAIL"} names the single representative frame Telegram itself
 *                  already generates, not the video itself.
 * @param mediaRemoteFileId Telegram's durable, session-independent file identifier for that
 *                          preview (TDLib's {@code remote.id}), or {@code null} when {@code
 *                          mediaKind} is {@code null}. Never a local, session-scoped file id --
 *                          those do not survive a restart or a fresh login.
 */
public record RemoteMessage(long messageId,
                            Long senderTgUserId,
                            String senderDisplayName,
                            Instant sentAt,
                            Instant editedAt,
                            boolean deleted,
                            String text,
                            Long replyToMessageId,
                            String mediaKind,
                            String mediaRemoteFileId) {
}
