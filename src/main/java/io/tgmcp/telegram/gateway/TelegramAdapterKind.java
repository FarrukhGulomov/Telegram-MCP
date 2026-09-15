package io.tgmcp.telegram.gateway;

public enum TelegramAdapterKind {
    /** Synthetic demo data. No Telegram account is contacted. */
    MOCK,
    /** Real TDLib client. Requires native runtime and explicit authorization to use. */
    TDLIB;

    /**
     * Whether this adapter can propagate upstream edits and deletions into {@code
     * TelegramGateway#fetchRevisions}. Security-audit findings F08/SEC-07: {@code TDLIB}
     * delivers edits and deletions as pushed {@code updateMessageContent} / {@code
     * updateDeleteMessages} events that nothing in this codebase currently turns into
     * revisions, so {@code fetchRevisions()} always reports zero for it -- not because
     * nothing changed, but because this adapter cannot see it. That is a structural,
     * adapter-wide gap, not a per-chat toggle, and {@link
     * io.tgmcp.config.ProductionSafetyValidator} refuses to authorize real-account access
     * against an adapter reporting {@code false} here unless the operator has explicitly
     * acknowledged it (see {@code tgmcp.telegram.acknowledge-missing-edit-deletion-pipeline}).
     */
    public boolean supportsLiveRevisions() {
        return this == MOCK;
    }
}
