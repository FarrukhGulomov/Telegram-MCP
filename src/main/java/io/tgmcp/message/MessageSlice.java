package io.tgmcp.message;

import java.util.List;

public record MessageSlice(List<StoredMessage> messages, boolean hasMore, MessagePageKey nextKey) {

    public static MessageSlice empty() {
        return new MessageSlice(List.of(), false, null);
    }
}
