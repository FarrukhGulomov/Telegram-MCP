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
import io.tgmcp.telegram.gateway.RemoteMessage;
import io.tgmcp.telegram.gateway.RemoteMessagePage;
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

    @Autowired
    private io.tgmcp.consent.ConsentService consentService;

    @Autowired
    private io.tgmcp.chat.ChatActivationService activationService;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private io.tgmcp.telegram.gateway.TelegramGateway gateway;

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

    /**
     * Security-audit finding F05: message storage is never gated on a per-sender consent
     * check -- pullForward/backfill fetch and store whatever falls in the approved date
     * window regardless of who sent it, so a participant's own messages, once stored, do not
     * automatically disappear just because that participant's own consent stops being valid.
     * Once someone with no valid consent for the chat's current context revision leaves, they
     * can never be asked again, so their own messages must be purged the moment their
     * departure is noticed -- not left to outlive the only chance anyone had to ask them.
     */
    @Test
    void purgesAParticipantsOwnMessagesWhenTheyLeaveWithoutValidConsent() {
        sync();
        long danaMessagesBefore = this.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM messages WHERE chat_id = ? AND sender_tg_user_id = ?",
                Long.class, ready().chat().id(), SyntheticDataset.DANA);
        assertThat(danaMessagesBefore).as("fixture sanity: Dana authored some seeded messages").isPositive();

        this.consentService.revokeParticipant(ready().team().actor(), ready().chat().id(),
                SyntheticDataset.DANA, "test: simulate consent no longer valid");
        mockGateway().removeParticipant(SyntheticDataset.ENGINEERING_CHAT, SyntheticDataset.DANA);
        sync();

        long danaMessagesAfter = this.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM messages WHERE chat_id = ? AND sender_tg_user_id = ?",
                Long.class, ready().chat().id(), SyntheticDataset.DANA);
        assertThat(danaMessagesAfter).as("Dana's own messages must be purged once she leaves without consent")
                .isZero();
        // Nobody else's messages are touched by this.
        assertThat(storedCount()).isEqualTo(30 - danaMessagesBefore);
    }

    /**
     * Security-audit finding F06: PAUSED.mayRetainContent() is true (a pause keeps what a
     * chat already has), which previously also let sync() keep fetching and storing *new*
     * content for a PAUSED chat -- reachable through the UI's manual "Sync now" button with no
     * separate check of its own. Only ACTIVE chats may ingest new content now. Paused directly
     * via the activation state (an operator's own Pause action) rather than through consent
     * lapsing, which per this deployment's explicit operator decision (see {@link
     * io.tgmcp.consent.ConsentService#coverageFor}) no longer pauses anything on its own.
     */
    @Test
    void manuallySyncingAPausedChatDoesNotIngestNewContent() {
        sync();
        long storedWhilePausing = storedCount();
        this.activations.updateState(ready().team().tenant().id(), ready().chat().id(),
                ActivationState.PAUSED, "test");

        mockGateway().appendMessage(SyntheticDataset.ENGINEERING_CHAT, SyntheticDataset.ALICE,
                "Alice Nakamura (synthetic)", "Posted while the chat is paused.");
        SyncOutcome manualSyncWhilePaused = sync();

        assertThat(manualSyncWhilePaused.status()).isNotEqualTo(SyncOutcome.Status.SYNCED);
        assertThat(manualSyncWhilePaused.messagesIngested()).isZero();
        assertThat(storedCount()).as("no new content may be ingested while paused")
                .isEqualTo(storedWhilePausing);
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
        assertThat(checkpoint().nextAttemptAt())
                .as("halted is a status label now, not a permanent stop -- it still schedules an "
                        + "automatic retry, just at the slow max-backoff cadence (1 minute in this profile) "
                        + "rather than the fast exponential ramp")
                .isEqualTo(this.clock.instant().plus(Duration.ofMinutes(1)));
    }

    @Test
    void haltsImmediatelyOnAnAuthenticationFailureButStillSchedulesAnAutomaticRetry() {
        mockGateway().failNextCall(new TelegramAuthException("session revoked"));

        assertThat(sync().status()).isEqualTo(SyncOutcome.Status.HALTED);
        SyncCheckpoint checkpoint = checkpoint();
        assertThat(checkpoint.halted()).isTrue();
        assertThat(checkpoint.consecutiveFailures()).isEqualTo(1);
        assertThat(checkpoint.lastErrorCode()).isEqualTo("AUTH_FAILED");
        assertThat(checkpoint.nextAttemptAt())
                .as("no operator action is possible or expected anywhere in this application, so even an "
                        + "auth failure must retry on its own eventually rather than wait for a human")
                .isEqualTo(this.clock.instant().plus(Duration.ofMinutes(1)));
    }

    @Test
    void haltsImmediatelyOnAPermissionFailure() {
        mockGateway().failNextCall(new TelegramPermissionException("kicked from chat"));

        assertThat(sync().status()).isEqualTo(SyncOutcome.Status.HALTED);
        assertThat(checkpoint().lastErrorCode()).isEqualTo("PERMISSION_DENIED");
    }

    /**
     * The scheduler runs unattended forever with no UI control to nudge it, so a halt can never
     * be a dead end: once its max-backoff window elapses, the very next automatic 30-second tick
     * retries on its own and recovers, exactly as if whatever caused the failure (a since-fixed
     * bug, a since-restored permission, a boot-time ownership race that resolved itself moments
     * later) had never happened -- no button, no operator, no manual step anywhere.
     */
    @Test
    void automaticallyRecoversOnceMaxBackoffElapsesEvenThoughItWasHalted() {
        mockGateway().failNextCall(new TelegramAuthException("session revoked"));
        assertThat(sync().status()).isEqualTo(SyncOutcome.Status.HALTED);
        long callsWhenHalted = mockGateway().callCount();

        this.mutableClock().advance(Duration.ofSeconds(30));
        assertThat(sync().status())
                .as("still inside the 1-minute max-backoff window -- no Telegram call yet")
                .isEqualTo(SyncOutcome.Status.SKIPPED_NOT_ACTIVE);
        assertThat(mockGateway().callCount()).isEqualTo(callsWhenHalted);

        this.mutableClock().advance(Duration.ofSeconds(31));
        SyncOutcome retried = sync();

        assertThat(retried.status())
                .as("halted must not mean stuck forever -- it retries automatically, no operator action needed")
                .isEqualTo(SyncOutcome.Status.SYNCED);
        assertThat(mockGateway().callCount()).isGreaterThan(callsWhenHalted);
        assertThat(checkpoint().halted()).isFalse();
    }

    /**
     * {@link ChatSyncService#syncNow} is not needed for eventual recovery any more (see {@link
     * #automaticallyRecoversOnceMaxBackoffElapsesEvenThoughItWasHalted}) -- it just skips the
     * remainder of the current backoff window, useful for an operator verifying a fix without
     * waiting.
     */
    @Test
    void syncNowSkipsTheRemainingBackoffAndResumesImmediately() {
        mockGateway().failNextCall(new TelegramAuthException("session revoked"));
        assertThat(sync().status()).isEqualTo(SyncOutcome.Status.HALTED);
        assertThat(checkpoint().halted()).isTrue();

        SyncOutcome resumed = this.syncService.syncNow(ready().team().tenant().id(), ready().chat().id());

        assertThat(resumed.status()).isEqualTo(SyncOutcome.Status.SYNCED);
        assertThat(checkpoint().halted()).isFalse();
        assertThat(checkpoint().consecutiveFailures()).isZero();
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

    // --- concurrent revoke (F07) --------------------------------------------------------

    @Test
    void abortsMidFetchWithoutStoringAnythingWhenTheActivationIsRevokedBeforeTheFirstCommit() {
        org.mockito.Mockito.doAnswer(invocation -> {
            Object page = invocation.callRealMethod();
            this.activationService.revoke(ready().team().actor(), ready().chat().id(), "revoked mid-fetch");
            return page;
        }).when(this.gateway).fetchMessages(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());

        SyncOutcome outcome = sync();

        assertThat(outcome.status()).isEqualTo(SyncOutcome.Status.HALTED);
        assertThat(outcome.messagesIngested()).isZero();
        assertThat(storedCount())
                .as("content fetched after a mid-flight revoke must never be committed")
                .isZero();
    }

    @Test
    void abortsWhenTheEpochMovesOnEvenIfTheStateReadsActiveAgain() {
        sync();
        long afterFirstSync = storedCount();
        assertThat(afterFirstSync).isPositive();

        this.checkpoints.reset(ready().team().tenant().id(), ready().chat().id());
        org.mockito.Mockito.doAnswer(invocation -> {
            Object page = invocation.callRealMethod();
            // Simulates the ABA case a plain state check cannot catch: the chat is revoked and
            // re-activated (same activation row, same ACTIVE state) while a fetch was already
            // in flight against the earlier epoch. A bare "is it still ACTIVE?" recheck would
            // pass here; only the fencing epoch tells this in-flight run it is stale.
            io.tgmcp.chat.ChatActivation current = this.activations
                    .find(ready().team().tenant().id(), ready().chat().id()).orElseThrow();
            this.activations.upsert(new io.tgmcp.chat.ChatActivation(current.id(), current.tenantId(),
                    current.chatId(), ActivationState.ACTIVE, current.epoch() + 1, current.purpose(),
                    current.providerScope(), current.consentVersion(), current.contextRevision(),
                    current.approvedFrom(), current.approvedTo(), current.retentionDays(),
                    current.membershipFingerprint(), current.pauseReason(), current.activatedAt(),
                    current.activatedByUserId()));
            return page;
        }).when(this.gateway).fetchMessages(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());

        SyncOutcome outcome = sync();

        assertThat(outcome.status()).isEqualTo(SyncOutcome.Status.HALTED);
        assertThat(outcome.messagesIngested()).isZero();
        assertThat(storedCount())
                .as("a stale run must not resume just because the state reads ACTIVE again")
                .isEqualTo(afterFirstSync);
    }

    // --- real-adapter-style pagination (F12) ---------------------------------------------

    @Test
    void pullForwardKeepsGoingPastAnEmptyPageThatStillReportsMoreContentFurtherBack() {
        // Security-audit finding F12: a source whose native pagination only walks backward
        // (the real TDLib adapter) can legitimately return zero matched messages on a page
        // while still reporting hasMore=true -- a backlog of messages newer than the approved
        // window can sit "in the way" of older, in-window content. ChatSyncService must keep
        // going (using the gateway's own resume cursor) rather than treating an empty page as
        // "nothing more to fetch", which is exactly what the old code did.
        RemoteMessage reachedAfterTheBacklog = new RemoteMessage(999L, SyntheticDataset.ALICE,
                "Alice Nakamura (synthetic)", this.clock.instant().minus(Duration.ofDays(1)), null, false,
                "finally past the backlog", null);

        org.mockito.Mockito.doReturn(new RemoteMessagePage(List.of(), true, 500L))
                .doReturn(new RemoteMessagePage(List.of(reachedAfterTheBacklog), false, null))
                .when(this.gateway).fetchMessages(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());

        SyncOutcome outcome = sync();

        assertThat(outcome.status()).isEqualTo(SyncOutcome.Status.SYNCED);
        assertThat(outcome.messagesIngested()).isPositive();
        assertThat(this.messages.history(ready().team().tenant().id(), ready().chat().id(),
                        this.clock.instant().minus(Duration.ofDays(2)), this.clock.instant(), null, 10)
                .messages())
                .extracting(StoredMessage::tgMessageId)
                .contains(999L);
    }

    private StoredMessage firstStored() {
        List<StoredMessage> page = this.messages.history(ready().team().tenant().id(),
                ready().chat().id(), this.clock.instant().minus(Duration.ofDays(365)),
                this.clock.instant(), null, 1).messages();
        assertThat(page).isNotEmpty();
        return page.get(0);
    }
}
