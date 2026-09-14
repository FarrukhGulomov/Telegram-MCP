package io.tgmcp.sync;

public record SyncOutcome(Status status, int messagesIngested, int revisionsApplied, String detail) {

    public enum Status {
        SYNCED,
        SKIPPED_NOT_ACTIVE,
        PAUSED_CONSENT,
        PAUSED_MEMBERSHIP_CHANGED,
        FLOOD_WAIT,
        RETRYABLE_FAILURE,
        HALTED
    }

    public static SyncOutcome skipped(String detail) {
        return new SyncOutcome(Status.SKIPPED_NOT_ACTIVE, 0, 0, detail);
    }
}
