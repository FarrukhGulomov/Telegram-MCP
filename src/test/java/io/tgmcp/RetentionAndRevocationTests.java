package io.tgmcp;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;
import io.tgmcp.chat.ActivationState;
import io.tgmcp.chat.ChatActivation;
import io.tgmcp.chat.ChatActivationRepository;
import io.tgmcp.chat.ChatActivationService;
import io.tgmcp.consent.ConsentService;
import io.tgmcp.mcp.McpCallContext;
import io.tgmcp.mcp.McpTools;
import io.tgmcp.message.MessageRepository;
import io.tgmcp.message.MessageSlice;
import io.tgmcp.security.McpScopes;
import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import io.tgmcp.sync.ChatSyncService;
import io.tgmcp.sync.RetentionService;
import io.tgmcp.telegram.mock.SyntheticDataset;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import static io.tgmcp.McpProtocolTests.asMap;
import static io.tgmcp.McpProtocolTests.errorCode;
import static io.tgmcp.McpProtocolTests.items;
import static io.tgmcp.McpProtocolTests.structured;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Retention and revocation.
 *
 * <p>The important distinction: revocation must block reads <em>immediately</em>, while the
 * data purge is a separate, idempotent step. A design that only deleted data would leave a
 * window where a request in flight still succeeds.
 */
class RetentionAndRevocationTests extends AbstractIntegrationTest {

    @Autowired
    private McpTools tools;

    @Autowired
    private ChatSyncService syncService;

    @Autowired
    private RetentionService retentionService;

    @Autowired
    private ChatActivationService activationService;

    @Autowired
    private ChatActivationRepository activations;

    @Autowired
    private ConsentService consentService;

    /** Spied so that a revocation can be injected between the read and the response. */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private MessageRepository messages;

    private McpCallContext context(Fixtures.Team team) {
        return new McpCallContext(team.actor(), McpScopes.ANTHROPIC_CLAUDE);
    }

    private McpSchema.CallToolResult search(Fixtures.ReadyChat chat, String query) {
        return this.tools.searchMessages(context(chat.team()), new McpSchema.CallToolRequest(
                "search_messages", Map.of(
                "chat_ids", java.util.List.of(chat.chat().id().toString()),
                "query", query,
                "from", this.clock.instant().minus(Duration.ofDays(90)).toString(),
                "to", this.clock.instant().toString())));
    }

    private Fixtures.ReadyChat synced() {
        Fixtures.ReadyChat chat = this.fixtures.readyEngineeringChat("acme", this.clock.instant());
        this.syncService.sync(chat.team().tenant().id(), chat.chat().id());
        return chat;
    }

    // --- revocation ---------------------------------------------------------------------

    @Test
    void blocksReadsImmediatelyOnRevocation() {
        Fixtures.ReadyChat chat = synced();
        assertThat(search(chat, "backpressure").isError()).isFalse();

        this.activationService.revoke(chat.team().actor(), chat.chat().id(), "participant withdrew");

        assertThat(errorCode(search(chat, "backpressure"))).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void deletesStoredMessagesWhenAccessIsRevoked() {
        Fixtures.ReadyChat chat = synced();
        assertThat(this.messages.countForChat(chat.team().tenant().id(), chat.chat().id())).isPositive();

        this.activationService.revoke(chat.team().actor(), chat.chat().id(), "participant withdrew");

        assertThat(this.messages.countForChat(chat.team().tenant().id(), chat.chat().id())).isZero();
    }

    @Test
    void revocationIsIdempotent() {
        Fixtures.ReadyChat chat = synced();

        this.activationService.revoke(chat.team().actor(), chat.chat().id(), "first");
        this.activationService.revoke(chat.team().actor(), chat.chat().id(), "second");

        assertThat(this.activations.find(chat.team().tenant().id(), chat.chat().id())
                .orElseThrow().state()).isEqualTo(ActivationState.REVOKED);
        assertThat(this.messages.countForChat(chat.team().tenant().id(), chat.chat().id())).isZero();
    }

    /**
     * Security-audit finding SEC-04: {@code activate()} never checked the activation's current
     * state at all -- only whether consent coverage happened to be complete -- so calling it on
     * a chat that had already been revoked would silently flip it straight back to ACTIVE.
     * Revocation must require a fresh {@code propose()} before a chat can be activated again.
     */
    @Test
    void activatingARevokedChatIsRefusedRatherThanSilentlyReactivatingIt() {
        Fixtures.ReadyChat chat = this.fixtures.readyEngineeringChat("sec04seq", this.clock.instant());
        this.activationService.revoke(chat.team().actor(), chat.chat().id(), "test revoke");

        assertThatThrownBy(() -> this.activationService.activate(chat.team().actor(), chat.chat().id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.chatRevoked");

        assertThat(this.activations.find(chat.team().tenant().id(), chat.chat().id())
                .orElseThrow().state()).isEqualTo(ActivationState.REVOKED);
    }

    /**
     * Security-audit finding SEC-04: {@code activate()} used to read the activation row with a
     * plain SELECT and later overwrite the whole row -- epoch included -- from that stale
     * read. A {@code revoke()} landing in between would have its atomic epoch bump silently
     * undone, and the chat could even flip back to ACTIVE right after being revoked. Row
     * locking makes the two calls serialize instead: whichever order they actually land in,
     * the chat must end up REVOKED with a strictly higher epoch than it started with -- never
     * ACTIVE, and never back at the epoch it had before either call ran.
     */
    @Test
    void concurrentActivateAndRevokeNeverLeaveTheChatActiveOrRegressTheEpoch() throws Exception {
        Fixtures.ReadyChat chat = this.fixtures.readyEngineeringChat("sec04conc", this.clock.instant());
        long originalEpoch = this.activations.find(chat.team().tenant().id(), chat.chat().id())
                .orElseThrow().epoch();

        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<?> activateAttempt = executor.submit(() -> {
                barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                try {
                    this.activationService.activate(chat.team().actor(), chat.chat().id());
                }
                catch (IllegalArgumentException ignored) {
                    // Acceptable: it lost the race and saw the chat already revoked.
                }
                return null;
            });
            java.util.concurrent.Future<?> revokeAttempt = executor.submit(() -> {
                barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                this.activationService.revoke(chat.team().actor(), chat.chat().id(), "concurrent test revoke");
                return null;
            });

            activateAttempt.get(10, java.util.concurrent.TimeUnit.SECONDS);
            revokeAttempt.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        finally {
            executor.shutdownNow();
        }

        ChatActivation finalState = this.activations.find(chat.team().tenant().id(), chat.chat().id())
                .orElseThrow();
        assertThat(finalState.state()).as("revoke must never be silently undone by a racing activate")
                .isEqualTo(ActivationState.REVOKED);
        assertThat(finalState.epoch()).as("the fencing epoch must never regress")
                .isEqualTo(originalEpoch + 1);
    }

    @Test
    void revokingOneParticipantsConsentPurgesOnlyTheirOwnMessagesImmediately() {
        // Security-audit finding F16: revoking a single participant's consent used to only
        // pause the chat -- and PAUSED.mayRetainContent() is true, so every message stayed on
        // disk regardless, contradicting the "revoking access deletes the stored copy"
        // promise. The purge must happen immediately, in the same call, not wait for that
        // participant to also physically leave the chat before F05's departure-purge logic
        // would ever notice.
        Fixtures.ReadyChat chat = synced();
        long danaMessagesBefore = this.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM messages WHERE chat_id = ? AND sender_tg_user_id = ?",
                Long.class, chat.chat().id(), SyntheticDataset.DANA);
        assertThat(danaMessagesBefore).as("fixture sanity: Dana authored some seeded messages").isPositive();
        long totalBefore = this.messages.countForChat(chat.team().tenant().id(), chat.chat().id());

        this.consentService.revokeParticipant(chat.team().actor(), chat.chat().id(), SyntheticDataset.DANA,
                "withdrew consent");

        long danaMessagesAfter = this.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM messages WHERE chat_id = ? AND sender_tg_user_id = ?",
                Long.class, chat.chat().id(), SyntheticDataset.DANA);
        assertThat(danaMessagesAfter)
                .as("Dana's own messages must be gone the instant her consent is revoked, before any resync")
                .isZero();
        assertThat(this.messages.countForChat(chat.team().tenant().id(), chat.chat().id()))
                .as("nobody else's messages are touched")
                .isEqualTo(totalBefore - danaMessagesBefore);
    }

    /**
     * The race that matters: consent is withdrawn after the rows have been read but before the
     * response is built. Spying on the repository makes that ordering deterministic instead of
     * hoping to hit it by timing.
     */
    @Test
    void deniesTheResponseWhenAccessIsRevokedBetweenTheReadAndTheReply() {
        Fixtures.ReadyChat chat = synced();

        org.mockito.Mockito.doAnswer(invocation -> {
            MessageSlice slice = (MessageSlice) invocation.callRealMethod();
            assertThat(slice.messages()).as("rows really were read before revocation").isNotEmpty();
            this.activationService.revoke(chat.team().actor(), chat.chat().id(), "revoked mid-request");
            return slice;
        }).when(this.messages).search(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());

        McpSchema.CallToolResult result = search(chat, "backpressure");

        assertThat(result.isError()).isTrue();
        assertThat(errorCode(result)).isEqualTo("ACCESS_DENIED");
        assertThat(structured(result)).doesNotContainKey("items");
    }

    /**
     * Security-audit finding SEC-05: a plain re-check of "is this chat currently allowed" is
     * vulnerable to an ABA cycle -- a chat can be revoked, re-proposed, re-consented and
     * reactivated again in the time it takes one slow request to finish, and the fresh
     * state-and-coverage check at the end of that request would find everything satisfied
     * again even though the buffered result set was fetched under an authorization instance
     * that no longer exists. Binding reverify() to the exact epoch and context revision
     * captured at query start catches this the same way ChatSyncService#requireNotFenced
     * already does for the write path (F07) -- and the chat is genuinely usable again
     * immediately afterwards, proving this isn't a general breakage.
     */
    @Test
    void deniesTheResponseAfterARevokeReproposeReconsentReactivateCycleEvenThoughAccessLooksFineAgain() {
        Fixtures.ReadyChat chat = synced();
        List<String> providers = java.util.Arrays.asList(chat.activation().providerScope().split(","));

        org.mockito.Mockito.doAnswer(invocation -> {
            MessageSlice slice = (MessageSlice) invocation.callRealMethod();
            assertThat(slice.messages()).as("rows really were read before the ABA cycle").isNotEmpty();

            this.activationService.revoke(chat.team().actor(), chat.chat().id(), "aba test: revoke");
            ChatActivation reproposed = this.activationService.propose(chat.team().actor(), chat.chat().id(),
                    chat.activation().purpose(), providers, chat.activation().approvedFrom(),
                    chat.activation().approvedTo(), chat.activation().retentionDays());
            this.fixtures.consentAll(chat.team(), chat.chat(), reproposed, this.clock.instant(),
                    this.clock.instant().plus(Duration.ofDays(300)));
            this.activationService.activate(chat.team().actor(), chat.chat().id());
            // propose() resets the sync checkpoint, so a fresh independent read afterwards
            // needs its own successful sync -- otherwise it fails on SYNC_PENDING, which would
            // have nothing to do with the epoch/contextRevision check this test is about.
            this.syncService.sync(chat.team().tenant().id(), chat.chat().id());
            return slice;
        }).doCallRealMethod() // only the first search() call triggers the ABA cycle
                .when(this.messages).search(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());

        McpSchema.CallToolResult result = search(chat, "backpressure");

        assertThat(result.isError()).as("the buffered response must not survive an ABA cycle").isTrue();
        assertThat(errorCode(result)).isEqualTo("ACCESS_DENIED");
        assertThat(structured(result)).doesNotContainKey("items");

        assertThat(this.activations.find(chat.team().tenant().id(), chat.chat().id())
                .orElseThrow().state()).as("the chat is genuinely ACTIVE again after the cycle")
                .isEqualTo(ActivationState.ACTIVE);
        assertThat(search(chat, "backpressure").isError())
                .as("a fresh, independent request against the now-current authorization succeeds")
                .isFalse();
    }

    /**
     * Security-audit finding SEC-03: the write-time gate in {@code ChatSyncService#store}
     * keeps this from happening going forward, but content already on disk -- ingested before
     * this fix shipped, or from a sender whose consent lapses between sync passes -- needs a
     * read-time backstop too. Inserted directly, bypassing the gate entirely, to prove the
     * read path itself refuses to surface it rather than depending on ingestion having stayed
     * clean.
     */
    @Test
    void neverSurfacesAMessageFromASenderWithNoConsentOnFileEvenIfAlreadyStored() {
        Fixtures.ReadyChat chat = synced();
        long ghostSender = 999_777L;
        this.jdbcTemplate.update("""
                        INSERT INTO messages (id, tenant_id, chat_id, tg_message_id, sender_tg_user_id,
                                               sender_display_name, sent_at, content)
                        VALUES (gen_random_uuid(), ?, ?, 999999, ?, 'Ghost (synthetic)', ?,
                                'backpressure applied by a ghost sender nobody ever consented for')
                        """,
                chat.team().tenant().id(), chat.chat().id(), ghostSender,
                java.sql.Timestamp.from(this.clock.instant()));

        McpSchema.CallToolResult result = search(chat, "backpressure");

        assertThat(items(result)).as("a genuine match from a consented sender is still returned")
                .isNotEmpty();
        assertThat(items(result))
                .as("a sender with no consent record on file must never be surfaced, even once stored")
                .noneMatch(item -> String.valueOf(ghostSender).equals(asMap(item.get("sender")).get("tgUserId")));
    }

    // --- retention ----------------------------------------------------------------------

    @Test
    void deletesMessagesOlderThanTheRetentionPeriod() {
        Fixtures.ReadyChat chat = this.fixtures.readyEngineeringChat("acme", this.clock.instant(),
                this.clock.instant().minus(Duration.ofDays(365)),
                this.clock.instant().plus(Duration.ofDays(365)), 365, McpScopes.ANTHROPIC_CLAUDE);
        this.syncService.sync(chat.team().tenant().id(), chat.chat().id());
        long before = this.messages.countForChat(chat.team().tenant().id(), chat.chat().id());
        assertThat(before).isPositive();

        // Tighten retention to 7 days; the synthetic history reaches back three weeks.
        ChatActivation tightened = withRetention(chat, 7);
        int deleted = this.retentionService.apply(tightened);

        assertThat(deleted).isPositive();
        assertThat(this.messages.countForChat(chat.team().tenant().id(), chat.chat().id()))
                .isLessThan(before);
    }

    @Test
    void retentionIsIdempotent() {
        Fixtures.ReadyChat chat = synced();
        ChatActivation tightened = withRetention(chat, 7);

        this.retentionService.apply(tightened);
        long afterFirst = this.messages.countForChat(chat.team().tenant().id(), chat.chat().id());

        assertThat(this.retentionService.apply(tightened)).isZero();
        assertThat(this.messages.countForChat(chat.team().tenant().id(), chat.chat().id()))
                .isEqualTo(afterFirst);
    }

    @Test
    void removesAgedOutMessagesFromSearchResultsToo() {
        Fixtures.ReadyChat chat = synced();
        assertThat(items(search(chat, "telemetry"))).isNotEmpty();

        this.retentionService.apply(withRetention(chat, 1));

        // Search reads the same rows through the generated tsvector column, so deleting the row
        // removes it from the index in the same statement.
        assertThat(items(search(chat, "telemetry"))).isEmpty();
    }

    @Test
    void purgesEverythingForAChatWhoseStateNoLongerAllowsRetention() {
        Fixtures.ReadyChat chat = synced();
        this.activations.updateState(chat.team().tenant().id(), chat.chat().id(),
                ActivationState.AWAITING_CONSENT, "context changed");

        this.retentionService.apply(this.activations.find(chat.team().tenant().id(),
                chat.chat().id()).orElseThrow());

        assertThat(this.messages.countForChat(chat.team().tenant().id(), chat.chat().id())).isZero();
    }

    @Test
    void deleteStoredDataRemovesContentWithoutChangingTheActivationState() {
        Fixtures.ReadyChat chat = synced();

        int deleted = this.activationService.deleteStoredData(chat.team().actor(), chat.chat().id());

        assertThat(deleted).isPositive();
        assertThat(this.messages.countForChat(chat.team().tenant().id(), chat.chat().id())).isZero();
        assertThat(this.activations.find(chat.team().tenant().id(), chat.chat().id())
                .orElseThrow().state()).isEqualTo(ActivationState.ACTIVE);
    }

    @Test
    void reportsCoverageHonestlyAfterRetentionHasTrimmedHistory() {
        Fixtures.ReadyChat chat = synced();
        this.retentionService.apply(withRetention(chat, 7));

        McpSchema.CallToolResult status = this.tools.getSyncStatus(context(chat.team()),
                new McpSchema.CallToolRequest("get_sync_status",
                        Map.of("chat_id", chat.chat().id().toString())));

        Map<String, Object> coverage = asMap(structured(status).get("coverage"));
        assertThat(coverage.get("retentionDays")).isEqualTo(7);
        assertThat((String) coverage.get("note")).contains("retention");
    }

    private ChatActivation withRetention(Fixtures.ReadyChat chat, int retentionDays) {
        ChatActivation existing = this.activations.find(chat.team().tenant().id(), chat.chat().id())
                .orElseThrow();
        ChatActivation updated = new ChatActivation(existing.id(), existing.tenantId(), existing.chatId(),
                existing.state(), existing.epoch(), existing.purpose(), existing.providerScope(), existing.consentVersion(),
                existing.contextRevision(), existing.approvedFrom(), existing.approvedTo(), retentionDays,
                existing.membershipFingerprint(), existing.pauseReason(), existing.activatedAt(),
                existing.activatedByUserId());
        this.activations.upsert(updated);
        return updated;
    }
}
