package io.tgmcp.access;

import java.time.Instant;
import java.util.Optional;

import io.tgmcp.chat.Chat;
import io.tgmcp.chat.ChatActivation;
import io.tgmcp.consent.ConsentCoverage;
import io.tgmcp.sync.SyncCheckpoint;

/**
 * A chat the caller is currently allowed to read, together with everything a response needs
 * in order to be honest about coverage and freshness.
 */
public record AuthorizedChat(Chat chat,
                             ChatActivation activation,
                             ConsentCoverage coverage,
                             SyncCheckpoint checkpoint) {

    /** The narrowest window allowed by the approved range and the retention policy. */
    public Instant windowStart(Instant now) {
        return this.activation.effectiveFrom(now);
    }

    public Instant windowEnd(Instant now) {
        return this.activation.approvedTo().isBefore(now) ? this.activation.approvedTo() : now;
    }

    public Optional<Instant> lastSuccessfulSync() {
        return Optional.ofNullable(this.checkpoint).map(SyncCheckpoint::lastSuccessAt);
    }

    public boolean partialHistory() {
        return this.checkpoint == null || !this.checkpoint.backfillComplete();
    }
}
