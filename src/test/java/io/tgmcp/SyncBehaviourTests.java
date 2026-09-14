package io.tgmcp;

import java.time.Duration;
import java.util.List;

import io.tgmcp.chat.ActivationState;
import io.tgmcp.chat.ChatActivationRepository;
import io.tgmcp.message.MessageRepository;
import io.tgmcp.message.StoredMessage;
import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import io.tgmcp.sync.ChatSyncService;
import io.tgmcp.sync.SyncCheckpoint;
import io.tgmcp.sync.SyncCheckpointRepository;
import io.tgmcp.sync.SyncOutcome;
import io.tgmcp.telegram.gateway.RemoteParticipant;
import io.tgmcp.telegram.gateway.TelegramAuthException;
import io.tgmcp.telegram.gateway.TelegramFloodWaitException;
import io.tgmcp.telegram.gateway.TelegramPermissionException;
import io.tgmcp.telegram.gateway.TelegramTransientException;
import io.tgmcp.telegram.mock.SyntheticDataset;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ingestion behaviour: idempotency, upstream mutations, restart recovery, and how failures
 * are classified into "wait and retry" versus "stop and ask a human".
 */
class SyncBehaviourTests extends AbstractIntegrationTest {

    @Autowired
    private ChatSyncService syncService;

    @Autowired
    private MessageRepository messages;

    @Autowired
    private SyncCheckpointRepository checkpoints;

    @Autowired
    private ChatActivationRepository activations;

    private Fixtures.ReadyChat chat;

    private Fixtures.ReadyChat ready() {
        if (this.chat == null) {
            this.chat = this.fixtures.readyEngineeringChat("acme", this.clock.instant());
        }
        return this.chat;
    }

    private SyncOutcome sync() {
        return this.syncService.sync(ready().team().tenant().id(), ready().chat().id());
    }

    private long storedCount() {
        return this.messages.countForChat(ready().team().tenant().id(), ready().chat().id());
    }

    private SyncCheckpoint checkpoint() {
        return this.checkpoints.find(ready().team().tenant().id(), ready().chat().id()).orElseThrow();
    }

    // --- idempotency and restart --------------------------------------------------------

    @Test
    void ingestsTheApprovedWindowOnTheFirstPass() {
        SyncOutcome outcome = sync();

        assertThat(outcome.status()).isEqualTo(SyncOutcome.Status.SYNCED);
        assertThat(outcome.messagesIngested()).isPositive();
        assertThat(storedCount()).isEqualTo(30);
    }

    @Test
    void reingestingTheSameMessagesChangesNothing() {
        sync();
        long afterFirst = storedCount();

        this.checkpoints.reset(ready().team().tenant().id(), ready().chat().id());
        sync();

        assertThat(storedCount())
                .as("a full re-run must not duplicate rows")
                .isEqualTo(afterFirst);
    }

    @Test
    void resumesFromTheCheckpointRatherThanRefetchingEverything() {
        sync();
        long firstPassCalls = mockGateway().callCount();
        mockGateway().appendMessage(SyntheticDataset.ENGINEERING_CHAT, SyntheticDataset.ALICE,
                "Alice Nakamura (synthetic)", "One more note about the launch checklist.");

        SyncOutcome second = sync();

        assertThat(second.messagesIngested()).isEqualTo(1);
        assertThat(storedCount()).isEqualTo(31);
        assertThat(mockGateway().callCount()).isGreaterThan(firstPassCalls);
    }

    @Test
    void recoversAfterASimulatedRestartWithoutLosingOrDuplicatingMessages() {
        sync();
        long before = storedCount();
        SyncCheckpoint saved = checkpoint();

        // A restart keeps only what is in the database: the checkpoint row.
        assertThat(saved.newestSyncedMessageId()).isNotNull();
        assertThat(saved.backfillComplete()).isTrue();

        mockGateway().appendMessage(SyntheticDataset.ENGINEERING_CHAT, SyntheticDataset.BEN,
                "Ben Okafor (synthetic)", "Posted while the service was down.");
        sync();

        assertThat(storedCount()).isEqualTo(before + 1);
    }

    // --- upstream mutations -------------------------------------------------------------

    @Test
    void appliesAnUpstreamEditInPlace() {
        sync();
        StoredMessage original = firstStored();

        mockGateway().editMessage(SyntheticDataset.ENGINEERING_CHAT, original.tgMessageId(),
                "Edited: the ring buffer is 8192 entries, not 4096.");
        this.mutableClock().advance(Duration.ofMinutes(1));
        sync();

        StoredMessage updated = this.messages.find(ready().team().tenant().id(), ready().chat().id(),
                original.tgMessageId()).orElseThrow();
        assertThat(updated.content()).startsWith("Edited:");
        assertThat(updated.editedAt()).isNotNull();
        assertThat(storedCount()).as("an edit is not a new message").isEqualTo(30);
    }

    @Test
    void dropsTheTextOfAMessageDeletedUpstreamAndStopsReturningIt() {
        sync();
        StoredMessage target = firstStored();

        mockGateway().deleteMessage(SyntheticDataset.ENGINEERING_CHAT, target.tgMessageId());
        this.mutableClock().advance(Duration.ofMinutes(1));
        sync();

        assertThat(this.messages.find(ready().team().tenant().id(), ready().chat().id(),
                target.tgMessageId()))
                .as("a deleted message is no longer readable")
                .isEmpty();
        assertThat(storedCount()).isEqualTo(29);

        // The tombstone keeps its text empty even if the message is offered again.
        String content = this.jdbcTemplate.queryForObject(
                "SELECT content FROM messages WHERE chat_id = ? AND tg_message_id = ?",
                String.class, ready().chat().id(), target.tgMessageId());
        assertThat(content).isEmpty();
    }

    @Test
    void doesNotResurrectADeletedMessageOnALaterPass() {
        sync();
        StoredMessage target = firstStored();
        mockGateway().deleteMessage(SyntheticDataset.ENGINEERING_CHAT, target.tgMessageId());
        this.mutableClock().advance(Duration.ofMinutes(1));
        sync();

        this.checkpoints.reset(ready().team().tenant().id(), ready().chat().id());
        this.mutableClock().advance(Duration.ofMinutes(1));
        sync();

        assertThat(this.messages.find(ready().team().tenant().id(), ready().chat().id(),
                target.tgMessageId())).isEmpty();
    }

    // --- membership -----------------------------------------------------------------------

    @Test
    void pausesTheChatWhenSomeoneJoinsSoConsentCanBeReviewedAgain() {
        sync();

        mockGateway().addParticipant(SyntheticDataset.ENGINEERING_CHAT,
                new RemoteParticipant(999_002L, "Newcomer (synthetic)", false));
        SyncOutcome outcome = sync();

        assertThat(outcome.status()).isEqualTo(SyncOutcome.Status.PAUSED_MEMBERSHIP_CHANGED);
        assertThat(this.activations.find(ready().team().tenant().id(), ready().chat().id())
                .orElseThrow().state()).isEqualTo(ActivationState.PAUSED);
    }

    /**
     * Security-audit finding F04: a chat that has already had a verified, non-empty
     * membership must not have a later empty response (a transient API glitch or a lost
     * permission, say) silently treated as "nothing changed" -- it needs to pause for review
     * exactly like a membership change does, instead of continuing to read under a membership
     * list that can no longer be confirmed.
     */
    @Test
    void pausesTheChatWhenAPreviouslyKnownMembershipComesBackEmpty() {
        sync();
        assertThat(this.activations.find(ready().team().tenant().id(), ready().chat().id())
                .orElseThrow().membershipFingerprint()).isNotNull();

        mockGateway().removeParticipant(SyntheticDataset.ENGINEERING_CHAT, SyntheticDataset.ALICE);
        mockGateway().removeParticipant(SyntheticDataset.ENGINEERING_CHAT, SyntheticDataset.BEN);
        mockGateway().removeParticipant(SyntheticDataset.ENGINEERING_CHAT, SyntheticDataset.CHIDI);
        mockGateway().removeParticipant(SyntheticDataset.ENGINEERING_CHAT, SyntheticDataset.DANA);
        mockGateway().removeParticipant(SyntheticDataset.ENGINEERING_CHAT, SyntheticDataset.HELPER_BOT);

        SyncOutcome outcome = sync();

        assertThat(outcome.status()).isEqualTo(SyncOutcome.Status.PAUSED_MEMBERSHIP_CHANGED);
        assertThat(outcome.detail()).contains("could not be verified");
        assertThat(this.activations.find(ready().team().tenant().id(), ready().chat().id())
                .orElseThrow().state()).isEqualTo(ActivationState.PAUSED);
    }

    @Test
    void pausesTheChatWhenConsentCoverageLapses() {
        sync();
        this.jdbcTemplate.update("UPDATE consent_records SET revoked_at = now() WHERE chat_id = ?",
                ready().chat().id());

        SyncOutcome outcome = sync();

        assertThat(outcome.status()).isEqualTo(SyncOutcome.Status.PAUSED_CONSENT);
        assertThat(this.activations.find(ready().team().tenant().id(), ready().chat().id())
                .orElseThrow().state()).isEqualTo(ActivationState.PAUSED);
    }

    // --- failure handling ------------------------------------------------------------------

    @Test
    void honoursAFloodWaitInsteadOfRetryingImmediately() {
        mockGateway().failNextCall(new TelegramFloodWaitException(Duration.ofSeconds(45)));

        SyncOutcome outcome = sync();

        assertThat(outcome.status()).isEqualTo(SyncOutcome.Status.FLOOD_WAIT);
        SyncCheckpoint checkpoint = checkpoint();
        assertThat(checkpoint.halted()).isFalse();
        assertThat(checkpoint.lastErrorCode()).isEqualTo("FLOOD_WAIT");
        assertThat(checkpoint.nextAttemptAt())
                .isEqualTo(this.clock.instant().plus(Duration.ofSeconds(45)));
    }

    @Test
    void refusesToRunAgainBeforeTheFloodWaitElapses() {
        mockGateway().failNextCall(new TelegramFloodWaitException(Duration.ofSeconds(45)));
        sync();
        long callsAfterFloodWait = mockGateway().callCount();

        SyncOutcome tooSoon = sync();
        assertThat(tooSoon.status()).isEqualTo(SyncOutcome.Status.SKIPPED_NOT_ACTIVE);
        assertThat(mockGateway().callCount())
                .as("no Telegram call is made while backing off")
                .isEqualTo(callsAfterFloodWait);

        this.mutableClock().advance(Duration.ofSeconds(46));
        assertThat(sync().status()).isEqualTo(SyncOutcome.Status.SYNCED);
    }

    @Test
    void backsOffExponentiallyForTransientFailuresAndHaltsAtTheLimit() {
        // The test profile sets max-consecutive-failures = 3.
        mockGateway().failNextCall(new TelegramTransientException("network blip"));
        assertThat(sync().status()).isEqualTo(SyncOutcome.Status.RETRYABLE_FAILURE);
        assertThat(checkpoint().nextAttemptAt()).isEqualTo(this.clock.instant().plus(Duration.ofSeconds(5)));

        this.mutableClock().advance(Duration.ofSeconds(6));
        mockGateway().failNextCall(new TelegramTransientException("network blip"));
        assertThat(sync().status()).isEqualTo(SyncOutcome.Status.RETRYABLE_FAILURE);
        assertThat(checkpoint().nextAttemptAt())
                .as("the delay doubles")
                .isEqualTo(this.clock.instant().plus(Duration.ofSeconds(10)));

        this.mutableClock().advance(Duration.ofSeconds(11));
        mockGateway().failNextCall(new TelegramTransientException("network blip"));
        assertThat(sync().status()).isEqualTo(SyncOutcome.Status.HALTED);
        assertThat(checkpoint().halted()).isTrue();
        assertThat(checkpoint().nextAttemptAt()).isNull();
    }

    @Test
    void haltsImmediatelyOnAnAuthenticationFailureRatherThanRetrying() {
        mockGateway().failNextCall(new TelegramAuthException("session revoked"));

        assertThat(sync().status()).isEqualTo(SyncOutcome.Status.HALTED);
        SyncCheckpoint checkpoint = checkpoint();
        assertThat(checkpoint.halted()).isTrue();
        assertThat(checkpoint.consecutiveFailures()).isEqualTo(1);
        assertThat(checkpoint.lastErrorCode()).isEqualTo("AUTH_FAILED");
    }

    @Test
    void haltsImmediatelyOnAPermissionFailure() {
        mockGateway().failNextCall(new TelegramPermissionException("kicked from chat"));

        assertThat(sync().status()).isEqualTo(SyncOutcome.Status.HALTED);
        assertThat(checkpoint().lastErrorCode()).isEqualTo("PERMISSION_DENIED");
    }

    @Test
    void makesNoFurtherTelegramCallsOnceHalted() {
        mockGateway().failNextCall(new TelegramAuthException("session revoked"));
        sync();
        long callsWhenHalted = mockGateway().callCount();

        this.mutableClock().advance(Duration.ofHours(6));
        sync();

        assertThat(mockGateway().callCount()).isEqualTo(callsWhenHalted);
    }

    // --- retention while not active ---------------------------------------------------------

    @Test
    void storesNothingWhileAChatIsAwaitingConsent() {
        sync();
        assertThat(storedCount()).isPositive();

        this.activations.updateState(ready().team().tenant().id(), ready().chat().id(),
                ActivationState.AWAITING_CONSENT, "re-collecting consent");
        SyncOutcome outcome = sync();

        assertThat(outcome.status()).isEqualTo(SyncOutcome.Status.SKIPPED_NOT_ACTIVE);
        assertThat(storedCount())
                .as("content must not be retained while a chat awaits activation")
                .isZero();
    }

    private StoredMessage firstStored() {
        List<StoredMessage> page = this.messages.history(ready().team().tenant().id(),
                ready().chat().id(), this.clock.instant().minus(Duration.ofDays(365)),
                this.clock.instant(), null, 1).messages();
        assertThat(page).isNotEmpty();
        return page.get(0);
    }
}
