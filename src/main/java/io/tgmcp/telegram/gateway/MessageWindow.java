package io.tgmcp.telegram.gateway;

import java.time.Instant;

/**
 * A bounded fetch request. There is no "fetch everything" option by design: callers must
 * always state a time range and a page limit.
 *
 * @param afterMessageId  exclusive lower bound on message id (incremental catch-up) -- the
 *                        fixed target boundary for a whole catch-up operation, never advanced
 *                        mid-operation except by the caller confirming new content stored
 * @param beforeMessageId exclusive upper bound on message id. For a backward ({@code
 *                        backfill}) window this is simply "resume backfilling from here". For
 *                        a forward ({@code forward}) window it plays a second, narrower role
 *                        (security-audit finding F12): a source whose native pagination can
 *                        only walk backward (TDLib's {@code getChatHistory}) may need several
 *                        raw pages to walk from the newest message down to {@code
 *                        afterMessageId}, and a gateway is free to stop early (returning {@code
 *                        hasMore=true} and its own resume point via {@link
 *                        RemoteMessagePage#resumeCursor()}) rather than do unbounded work in
 *                        one call -- the caller then passes that resume point back in as this
 *                        window's {@code beforeMessageId} on its next call, leaving {@code
 *                        afterMessageId} unchanged for the whole operation.
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
        return forward(from, to, afterMessageId, null, limit);
    }

    /** As {@link #forward(Instant, Instant, Long, int)}, resuming raw traversal from a gateway-supplied point. */
    public static MessageWindow forward(Instant from, Instant to, Long afterMessageId, Long resumeBeforeMessageId,
                                        int limit) {
        return new MessageWindow(from, to, afterMessageId, resumeBeforeMessageId, limit);
    }

    public static MessageWindow backward(Instant from, Instant to, Long beforeMessageId, int limit) {
        return new MessageWindow(from, to, null, beforeMessageId, limit);
    }
}
