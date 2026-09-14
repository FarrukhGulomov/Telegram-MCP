package io.tgmcp.telegram.gateway;

import java.util.List;

/** Messages in ascending {@code messageId} order, plus whether the window was truncated. */
public record RemoteMessagePage(List<RemoteMessage> messages, boolean hasMore) {

    public static RemoteMessagePage empty() {
        return new RemoteMessagePage(List.of(), false);
    }
}
