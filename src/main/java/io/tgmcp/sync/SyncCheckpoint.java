package io.tgmcp.sync;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable sync position for one chat. Ingestion is restartable because everything needed
 * to resume — the newest and oldest ids already stored, and whether backfill finished — is
 * persisted rather than held in memory.
 */
public record SyncCheckpoint(UUID id,
                             UUID tenantId,
                             UUID chatId,
                             Long newestSyncedMessageId,
                             Long oldestSyncedMessageId,
                             Instant syncedFrom,
                             Instant syncedTo,
                             boolean backfillComplete,
                             Instant lastAttemptAt,
                             Instant lastSuccessAt,
                             int consecutiveFailures,
                             Instant nextAttemptAt,
                             String lastErrorCode,
                             String lastErrorMessage,
                             boolean halted) {

    /**
     * "Halted" is purely a status label for the UI/audit trail now -- every automatic retry
     * decision, halted or not, is driven by {@code nextAttemptAt} alone, so a chat can never get
     * permanently stuck waiting on a human to notice and intervene (see {@code
     * ChatSyncService#handleGatewayFailure}).
     */
    public boolean dueAt(Instant now) {
        return this.nextAttemptAt == null || !now.isBefore(this.nextAttemptAt);
    }

    public boolean everSucceeded() {
        return this.lastSuccessAt != null;
    }
}
