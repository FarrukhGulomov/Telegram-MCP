package io.tgmcp.telegram.gateway;

import java.util.List;

/**
 * Messages in ascending {@code messageId} order, plus whether the window was truncated.
 *
 * @param resumeCursor when {@code hasMore} is true, an optional gateway-supplied point to pass
 *                      back as the next {@link MessageWindow}'s {@code beforeMessageId} so raw
 *                      traversal resumes exactly where this call left off, rather than the
 *                      caller having to (incorrectly) derive one from {@code messages()} --
 *                      which can be empty even while {@code hasMore} is true, when every
 *                      message examined so far fell outside the requested window (security
 *                      audit finding F12). Null when the caller's existing cursor derivation
 *                      is sufficient (the mock adapter never needs this).
 */
public record RemoteMessagePage(List<RemoteMessage> messages, boolean hasMore, Long resumeCursor) {

    public static RemoteMessagePage empty() {
        return new RemoteMessagePage(List.of(), false, null);
    }
}
