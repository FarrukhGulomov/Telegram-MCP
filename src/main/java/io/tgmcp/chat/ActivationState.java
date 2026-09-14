package io.tgmcp.chat;

public enum ActivationState {
    /** Discovered but never approved. Nothing is synced and nothing is stored. */
    INACTIVE,
    /**
     * An operator has proposed activation but participant consent coverage is incomplete.
     * No message content is retained while a chat sits in this state.
     */
    AWAITING_CONSENT,
    ACTIVE,
    /** Temporarily blocked: operator pause, or consent coverage became uncertain. */
    PAUSED,
    /** Consent withdrawn or the chat was switched off. Access is blocked immediately. */
    REVOKED;

    public boolean readable() {
        return this == ACTIVE;
    }

    /** States in which retaining message content is permitted. */
    public boolean mayRetainContent() {
        return this == ACTIVE || this == PAUSED;
    }
}
