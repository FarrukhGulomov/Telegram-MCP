package io.tgmcp.message;

import java.time.Instant;

/** What is actually stored for a chat, as opposed to what was approved. */
public record ChatCoverage(Instant earliestStored, Instant latestStored, long storedMessageCount) {

    public static ChatCoverage none() {
        return new ChatCoverage(null, null, 0L);
    }
}
