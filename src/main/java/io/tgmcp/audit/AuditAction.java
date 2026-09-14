package io.tgmcp.audit;

/** Actions worth an immutable record. Message text is never part of an audit event. */
public final class AuditAction {
    public static final String UI_SIGN_IN = "UI_SIGN_IN";
    public static final String TELEGRAM_CONNECT_STARTED = "TELEGRAM_CONNECT_STARTED";
    public static final String TELEGRAM_CONNECTED = "TELEGRAM_CONNECTED";
    public static final String TELEGRAM_DISCONNECTED = "TELEGRAM_DISCONNECTED";
    public static final String CHAT_DISCOVERED = "CHAT_DISCOVERED";
    public static final String CONSENT_RECORDED = "CONSENT_RECORDED";
    public static final String CONSENT_REVOKED = "CONSENT_REVOKED";
    public static final String CHAT_ACTIVATED = "CHAT_ACTIVATED";
    public static final String CHAT_PAUSED = "CHAT_PAUSED";
    public static final String CHAT_REVOKED = "CHAT_REVOKED";
    public static final String CHAT_SETTINGS_CHANGED = "CHAT_SETTINGS_CHANGED";
    public static final String MEMBERSHIP_CHANGED = "MEMBERSHIP_CHANGED";
    public static final String SYNC_SUCCEEDED = "SYNC_SUCCEEDED";
    public static final String SYNC_FAILED = "SYNC_FAILED";
    public static final String SYNC_FLOOD_WAIT = "SYNC_FLOOD_WAIT";
    public static final String SYNC_HALTED = "SYNC_HALTED";
    public static final String RETENTION_PURGE = "RETENTION_PURGE";
    public static final String DATA_DELETED = "DATA_DELETED";
    public static final String MCP_TOOL_CALL = "MCP_TOOL_CALL";
    public static final String MCP_ACCESS_DENIED = "MCP_ACCESS_DENIED";
    public static final String MCP_RATE_LIMITED = "MCP_RATE_LIMITED";

    private AuditAction() {
    }
}
