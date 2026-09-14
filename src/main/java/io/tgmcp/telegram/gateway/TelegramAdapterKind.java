package io.tgmcp.telegram.gateway;

public enum TelegramAdapterKind {
    /** Synthetic demo data. No Telegram account is contacted. */
    MOCK,
    /** Real TDLib client. Requires native runtime and explicit authorization to use. */
    TDLIB
}
