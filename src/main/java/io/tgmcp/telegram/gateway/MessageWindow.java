package io.tgmcp.telegram.gateway;

import java.time.Instant;

/**
 * A bounded fetch request. There is no "fetch everything" option by design: callers must
 * always state a time range and a page limit.
 *
 * @param afterMessageId  exclusive lower bound on message id (incremental catch-up)
 * @param beforeMessageId exclusive upper bound on message id (backwards backfill)
 */
public record MessageWindow(Instant from, Instant to, Long afterMessageId, Long beforeMessageId, int limit) {

    public MessageWindow {
        if (from == null || to == null) {
            throw new IllegalArgumentException("MessageWindow requires an explicit from/to range");
        }
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("MessageWindow requires to > from");
        }
        if (limit <= 0 || limit > 500) {
            throw new IllegalArgumentException("MessageWindow limit must be between 1 and 500");
        }
    }

    public static MessageWindow forward(Instant from, Instant to, Long afterMessageId, int limit) {
        return new MessageWindow(from, to, afterMessageId, null, limit);
    }

    public static MessageWindow backward(Instant from, Instant to, Long beforeMessageId, int limit) {
        return new MessageWindow(from, to, null, beforeMessageId, limit);
    }
}
