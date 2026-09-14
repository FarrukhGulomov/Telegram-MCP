package io.tgmcp;

import java.time.Duration;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;
import io.tgmcp.chat.ActivationState;
import io.tgmcp.chat.ChatActivation;
import io.tgmcp.chat.ChatActivationRepository;
import io.tgmcp.chat.ChatActivationService;
import io.tgmcp.mcp.McpCallContext;
import io.tgmcp.mcp.McpTools;
import io.tgmcp.message.MessageRepository;
import io.tgmcp.message.MessageSlice;
import io.tgmcp.security.McpScopes;
import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import io.tgmcp.sync.ChatSyncService;
import io.tgmcp.sync.RetentionService;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import static io.tgmcp.McpProtocolTests.asMap;
import static io.tgmcp.McpProtocolTests.errorCode;
import static io.tgmcp.McpProtocolTests.items;
import static io.tgmcp.McpProtocolTests.structured;
import static org.assertj.core.api.Assertions.assertThat;

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
