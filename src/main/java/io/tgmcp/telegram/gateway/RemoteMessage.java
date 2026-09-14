package io.tgmcp.telegram.gateway;

import java.time.Instant;

/**
 * A single message as reported by Telegram.
 *
 * <p>{@code text} is untrusted third-party data. It is stored and returned as data only.
 */
public record RemoteMessage(long messageId,
                            Long senderTgUserId,
                            String senderDisplayName,
                            Instant sentAt,
                            Instant editedAt,
                            boolean deleted,
                            String text,
                            Long replyToMessageId) {
}
