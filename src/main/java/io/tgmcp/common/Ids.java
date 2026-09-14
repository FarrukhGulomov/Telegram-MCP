package io.tgmcp.common;

import java.util.UUID;

public final class Ids {
    private Ids() {
    }

    public static UUID newId() {
        return UUID.randomUUID();
    }

    /** Parses a UUID without throwing; returns null for anything unparseable. */
    public static UUID parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
