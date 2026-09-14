package io.tgmcp;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import io.tgmcp.chat.ActivationState;
import io.tgmcp.chat.ChatActivation;
import io.tgmcp.chat.ChatActivationService;
import io.tgmcp.consent.ConsentMethod;
import io.tgmcp.consent.ConsentService;
import io.tgmcp.security.McpScopes;
import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Security-audit finding F03 (P0): consent used to be matched only against purpose + provider
 * scope + consent version. approvedFrom/approvedTo/retentionDays were never part of what a
 * consent record covered, and reverting a context back to values that happened to match an
 * older, still-valid consent record would let it silently count as covering the "new" state.
 *
 * <p>ChatActivation now carries an opaque {@code contextRevision} that changes whenever any
 * part of the processing context changes (purpose, provider scope, consent version, approved
 * range, or retention -- retention specifically was previously excluded from the change check).
 * A consent record only ever covers the exact revision it was granted under.
 */
class ConsentContextRevisionTests extends AbstractIntegrationTest {

    @Autowired
    private ChatActivationService activationService;

    @Autowired
    private ConsentService consentService;

    @Test
    void wideningRetentionAloneNowInvalidatesExistingConsent() {
        Fixtures.ReadyChat ready = this.fixtures.readyEngineeringChat("f03-retention", this.clock.instant());
        ChatActivation before = ready.activation();
        assertThat(this.consentService.coverageFor(before).complete()).isTrue();

        ChatActivation afterWidening = this.activationService.updateSettings(ready.team().actor(),
                ready.chat().id(), before.purpose(), List.copyOf(before.providers()),
                before.approvedFrom(), before.approvedTo(), before.retentionDays() + 30);

        assertThat(afterWidening.state()).isEqualTo(ActivationState.AWAITING_CONSENT);
        assertThat(afterWidening.contextRevision()).isNotEqualTo(before.contextRevision());
        assertThat(this.consentService.coverageFor(afterWidening).complete())
                .as("the old consent must not cover a silently widened retention window")
                .isFalse();
    }

    @Test
    void revertingToAPreviousPurposeDoesNotResurrectTheConsentGrantedForIt() {
        Instant now = this.clock.instant();
        Fixtures.ReadyChat ready = this.fixtures.readyEngineeringChat("f03-revert", now);
        Fixtures.Team team = ready.team();
        ChatActivation revisionA = ready.activation();
        assertThat(this.consentService.coverageFor(revisionA).complete()).isTrue();

        // Move to a new purpose ("B"): a fresh revision, no consent recorded for it yet.
        ChatActivation revisionB = this.activationService.updateSettings(team.actor(), ready.chat().id(),
                "Purpose B", List.copyOf(revisionA.providers()), revisionA.approvedFrom(),
                revisionA.approvedTo(), revisionA.retentionDays());
        assertThat(revisionB.state()).isEqualTo(ActivationState.AWAITING_CONSENT);
        assertThat(this.consentService.coverageFor(revisionB).complete()).isFalse();

        // Consent B, activate it for real.
        for (var participant : Fixtures.ENGINEERING_MEMBERS) {
            if (participant.bot()) {
                continue;
            }
            this.consentService.record(team.actor(), ready.chat().id(), participant.tgUserId(),
                    participant.displayName(), revisionB.purpose(), revisionB.providerScope(),
                    revisionB.consentVersion(), revisionB.contextRevision(), ConsentMethod.DEMO_SYNTHETIC,
                    "test", now.plus(Duration.ofDays(300)));
        }
        ChatActivation activatedB = this.activationService.activate(team.actor(), ready.chat().id());
        assertThat(activatedB.state()).isEqualTo(ActivationState.ACTIVE);

        // Revert back to the original purpose ("A"). Field values now match revisionA exactly,
        // but this must still be a brand-new revision -- neither the original A consent nor the
        // B consent may cover it.
        ChatActivation revertedToA = this.activationService.updateSettings(team.actor(), ready.chat().id(),
                revisionA.purpose(), List.copyOf(revisionA.providers()), revisionA.approvedFrom(),
                revisionA.approvedTo(), revisionA.retentionDays());

        assertThat(revertedToA.state()).isEqualTo(ActivationState.AWAITING_CONSENT);
        assertThat(revertedToA.contextRevision())
                .isNotEqualTo(revisionA.contextRevision())
                .isNotEqualTo(revisionB.contextRevision());
        assertThat(this.consentService.coverageFor(revertedToA).complete())
                .as("neither the original nor the intermediate consent may cover the reverted context")
                .isFalse();
        assertThatThrownBy(() -> this.activationService.activate(team.actor(), ready.chat().id()))
                .isInstanceOf(IllegalStateException.class);
    }
}
