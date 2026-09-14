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

    public boolean dueAt(Instant now) {
        return !this.halted && (this.nextAttemptAt == null || !now.isBefore(this.nextAttemptAt));
    }

    public boolean everSucceeded() {
        return this.lastSuccessAt != null;
    }
}
