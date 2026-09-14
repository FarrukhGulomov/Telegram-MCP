package io.tgmcp.ui;

import java.time.Instant;
import java.util.List;

import io.tgmcp.chat.ActivationState;
import io.tgmcp.chat.Chat;
import io.tgmcp.chat.ChatActivation;
import io.tgmcp.consent.ConsentCoverage;
import io.tgmcp.message.ChatCoverage;
import io.tgmcp.sync.SyncCheckpoint;

/** Everything one row of the chat table needs, precomputed so templates stay logic-free. */
public record ChatView(Chat chat,
                       ChatActivation activation,
                       ConsentCoverage coverage,
                       SyncCheckpoint checkpoint,
                       ChatCoverage stored,
                       int participantCount) {

    public String stateLabel() {
        return this.activation == null ? ActivationState.INACTIVE.name() : this.activation.state().name();
    }

    public boolean active() {
        return this.activation != null && this.activation.state() == ActivationState.ACTIVE;
    }

    public boolean selected() {
        return this.activation != null;
    }

    public boolean consentComplete() {
        return this.coverage != null && this.coverage.complete();
    }

    public boolean partialHistory() {
        return this.checkpoint == null || !this.checkpoint.backfillComplete();
    }

    public Instant lastSuccessAt() {
        return this.checkpoint == null ? null : this.checkpoint.lastSuccessAt();
    }

    public String syncState() {
        if (this.checkpoint == null) {
            return "NEVER_STARTED";
        }
        if (this.checkpoint.halted()) {
            return "HALTED";
        }
        return this.checkpoint.lastSuccessAt() == null ? "PENDING" : "HEALTHY";
    }

    public String lastErrorCode() {
        return this.checkpoint == null ? null : this.checkpoint.lastErrorCode();
    }

    public List<String> providers() {
        return this.activation == null ? List.of() : List.copyOf(this.activation.providers());
    }
}
