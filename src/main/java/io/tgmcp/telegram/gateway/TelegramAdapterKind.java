package io.tgmcp.telegram.gateway;

public enum TelegramAdapterKind {
    /** Synthetic demo data. No Telegram account is contacted. */
    MOCK,
    /** Real TDLib client. Requires native runtime and explicit authorization to use. */
    TDLIB;

    /**
     * Whether this adapter's edit/deletion pipeline into {@code TelegramGateway#fetchRevisions}
     * is considered production-verified. Security-audit findings F08/SEC-07: {@code TDLIB}
     * delivers edits and deletions as pushed {@code updateMessageContent} / {@code
     * updateDeleteMessages} events; {@link io.tgmcp.telegram.tdlib.TdlibTelegramGateway} now
     * listens for both and buffers them for {@code fetchRevisions()} to drain, so this is no
     * longer "nothing implemented" -- but that pipeline has never been exercised against a real
     * Telegram account (see docs/production-prerequisites.md), and this flag exists to gate
     * real-account access on an explicit, informed decision, not to claim it is proven. {@link
     * io.tgmcp.config.ProductionSafetyValidator} refuses to authorize real-account access
     * against an adapter reporting {@code false} here unless the operator has explicitly
     * acknowledged it (see {@code tgmcp.telegram.acknowledge-missing-edit-deletion-pipeline}).
     */
    public boolean supportsLiveRevisions() {
        return this == MOCK;
    }
}
