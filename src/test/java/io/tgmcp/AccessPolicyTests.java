package io.tgmcp;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.modelcontextprotocol.spec.McpSchema;
import io.tgmcp.chat.ActivationState;
import io.tgmcp.chat.Chat;
import io.tgmcp.chat.ChatActivation;
import io.tgmcp.chat.ChatActivationRepository;
import io.tgmcp.mcp.McpCallContext;
import io.tgmcp.mcp.McpToolNames;
import io.tgmcp.mcp.McpTools;
import io.tgmcp.security.McpScopes;
import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import io.tgmcp.sync.ChatSyncService;
import io.tgmcp.telegram.TelegramConnectionRepository;
import io.tgmcp.telegram.gateway.LoginStage;
import io.tgmcp.telegram.mock.SyntheticDataset;
import io.tgmcp.tenancy.AuthenticatedActor;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import static io.tgmcp.McpProtocolTests.asMap;
import static io.tgmcp.McpProtocolTests.errorCode;
import static io.tgmcp.McpProtocolTests.items;
import static io.tgmcp.McpProtocolTests.structured;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The authorization rules, exercised through the same service every access path uses.
 *
 * <p>Two behaviours matter most here: a caller must never be able to tell an unauthorized
 * chat apart from a non-existent one, and consent must be re-evaluated against the *current*
 * state on every call rather than trusted from activation time.
 */
class AccessPolicyTests extends AbstractIntegrationTest {

    @Autowired
    private McpTools tools;

    @Autowired
    private ChatSyncService syncService;

    @Autowired
    private ChatActivationRepository activations;

    @Autowired
    private TelegramConnectionRepository connections;

    private McpCallContext contextFor(Fixtures.Team team) {
        return new McpCallContext(team.actor(), McpScopes.ANTHROPIC_CLAUDE);
    }

    private McpSchema.CallToolResult history(Fixtures.Team team, UUID chatId) {
        return this.tools.getChatHistory(contextFor(team), new McpSchema.CallToolRequest(
                "get_chat_history", Map.of(
                "chat_id", chatId.toString(),
                "from", this.clock.instant().minus(Duration.ofDays(90)).toString(),
                "to", this.clock.instant().toString())));
    }

    // --- tenant isolation ---------------------------------------------------------------

    @Test
    void deniesAChatBelongingToAnotherTenant() {
        Fixtures.ReadyChat acme = this.fixtures.readyEngineeringChat("acme", this.clock.instant());
        this.syncService.sync(acme.team().tenant().id(), acme.chat().id());
        Fixtures.ReadyChat globex = this.fixtures.readyEngineeringChat("globex", this.clock.instant());

        // Globex asks for Acme's chat id, which it could only have obtained out of band.
        McpSchema.CallToolResult result = history(globex.team(), acme.chat().id());

        assertThat(result.isError()).isTrue();
        assertThat(errorCode(result)).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void neverListsAnotherTenantsChats() {
        this.fixtures.readyEngineeringChat("acme", this.clock.instant());
        Fixtures.ReadyChat globex = this.fixtures.readyEngineeringChat("globex", this.clock.instant());

        McpSchema.CallToolResult result = this.tools.listAllowedChats(contextFor(globex.team()),
                new McpSchema.CallToolRequest("list_allowed_chats", Map.of()));

        assertThat(items(result)).hasSize(1);
        assertThat(items(result).get(0).get("chatId")).isEqualTo(globex.chat().id().toString());
    }

    @Test
    void reportsAnUnknownChatIdExactlyLikeAnUnauthorizedOne() {
        Fixtures.ReadyChat acme = this.fixtures.readyEngineeringChat("acme", this.clock.instant());
        this.syncService.sync(acme.team().tenant().id(), acme.chat().id());

        Fixtures.ReadyChat globex = this.fixtures.readyEngineeringChat("globex", this.clock.instant());
        // A chat that exists elsewhere, and a chat that exists nowhere, must be indistinguishable.
        McpSchema.CallToolResult otherTenant = history(globex.team(), acme.chat().id());
        McpSchema.CallToolResult nonexistent = history(globex.team(), UUID.randomUUID());

        assertThat(errorCode(otherTenant)).isEqualTo(errorCode(nonexistent)).isEqualTo("ACCESS_DENIED");
        assertThat(McpProtocolTests.structured(otherTenant))
                .isEqualTo(McpProtocolTests.structured(nonexistent));
    }

    // --- activation state ---------------------------------------------------------------

    @Test
    void deniesAChatThatWasNeverActivated() {
        Fixtures.ReadyChat chat = this.fixtures.readyEngineeringChat("acme", this.clock.instant());
        this.activations.updateState(chat.team().tenant().id(), chat.chat().id(),
                ActivationState.INACTIVE, null);

        assertThat(errorCode(history(chat.team(), chat.chat().id()))).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void reportsAwaitingConsentAsConsentRequiredRatherThanADenial() {
        Fixtures.ReadyChat chat = this.fixtures.readyEngineeringChat("acme", this.clock.instant());
        this.activations.updateState(chat.team().tenant().id(), chat.chat().id(),
                ActivationState.AWAITING_CONSENT, "collecting");

        assertThat(errorCode(history(chat.team(), chat.chat().id()))).isEqualTo("CONSENT_REQUIRED");
    }

    /**
     * Security-audit finding SEC-06: coverage is only ever computed from the participant list
     * last confirmed by a successful sync -- once upstream authorization is lost (the account
     * is logged out, banned, or loses permission), that list can never be reconfirmed again,
     * yet nothing previously stopped MCP reads from continuing to trust it indefinitely. Once
     * the last successful sync is older than the configured freshness budget (PT2H in the test
     * profile), the chat must stop being readable, exactly as if membership were unknown.
     */
    @Test
    void blocksReadsOnceMembershipHasNotBeenReconfirmedWithinTheFreshnessBudget() {
        Fixtures.ReadyChat chat = this.fixtures.readyEngineeringChat("sec06-stale", this.clock.instant());
        this.syncService.sync(chat.team().tenant().id(), chat.chat().id());
        assertThat(history(chat.team(), chat.chat().id()).isError())
                .as("freshly synced, this chat is readable").isFalse();

        this.mutableClock().advance(Duration.ofHours(3));

        McpSchema.CallToolResult result = history(chat.team(), chat.chat().id());
        assertThat(result.isError()).isTrue();
        assertThat(errorCode(result)).isEqualTo("CONSENT_REQUIRED");
    }

    /**
     * Security-audit finding SEC-R03: the freshness check above only ever fires once staleness
     * has accumulated for a full budget window -- it says nothing about a connection known to be
     * down *right now* (a remote logout, a ban, or any other loss of authorization {@code
     * TelegramConnectionService#applyAsyncStateChange} observes). A chat must stop being readable
     * the instant its tenant's Telegram connection is not READY, not only after the freshness
     * budget has separately expired.
     */
    @Test
    void blocksReadsWhileTheTenantsTelegramConnectionIsNotReady() {
        Fixtures.ReadyChat chat = readyAndSynced("sec-r03-disconnected");
        assertThat(history(chat.team(), chat.chat().id()).isError())
                .as("connected and freshly synced, this chat is readable").isFalse();

        this.connections.updateStatus(chat.team().tenant().id(), chat.team().connection().id(),
                LoginStage.WAIT_CODE, null, null, null, null);

        McpSchema.CallToolResult result = history(chat.team(), chat.chat().id());
        assertThat(result.isError()).isTrue();
        assertThat(errorCode(result)).isEqualTo("CONNECTION_UNAVAILABLE");
    }

    @Test
    void deniesARevokedChatWithoutRevealingThatItOnceExisted() {
        Fixtures.ReadyChat chat = this.fixtures.readyEngineeringChat("acme", this.clock.instant());
        this.activations.updateState(chat.team().tenant().id(), chat.chat().id(),
                ActivationState.REVOKED, "withdrawn");

        assertThat(errorCode(history(chat.team(), chat.chat().id()))).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void reportsSyncPendingWhenAChatIsApprovedButNothingHasBeenSynchronised() {
        Fixtures.ReadyChat chat = this.fixtures.readyEngineeringChat("acme", this.clock.instant());

        // No sync run yet.
        assertThat(errorCode(history(chat.team(), chat.chat().id()))).isEqualTo("SYNC_PENDING");
    }

    // --- consent ------------------------------------------------------------------------
    // Per this deployment's explicit operator decision (see ConsentService#coverageFor), no
    // per-participant consent record gates MCP access any more -- the tests that used to
    // assert CONSENT_REQUIRED for a missing/expired/version-mismatched/unknown participant
    // consent record have been removed; access denial now only ever comes from the activation
    // state itself (see reportsAwaitingConsentAsConsentRequiredRatherThanADenial above) or
    // staleness (see blocksReadsOnceMembershipHasNotBeenReconfirmedWithinTheFreshnessBudget).

    @Test
    void treatsABotAsSomeoneWhoseConsentIsNotRequired() {
        Fixtures.ReadyChat chat = readyAndSynced("acme");

        // The bot never consented, yet the chat is readable: only humans are counted.
        assertThat(history(chat.team(), chat.chat().id()).isError()).isFalse();
    }

    // --- provider scope -----------------------------------------------------------------

    @Test
    void deniesAProviderThatTheActivationDoesNotCover() {
        Fixtures.ReadyChat chat = readyAndSynced("acme");
        McpCallContext chatgpt = new McpCallContext(chat.team().actor(), McpScopes.OPENAI_CHATGPT);

        McpSchema.CallToolResult result = this.tools.getChatHistory(chatgpt,
                new McpSchema.CallToolRequest("get_chat_history", Map.of(
                        "chat_id", chat.chat().id().toString(),
                        "from", this.clock.instant().minus(Duration.ofDays(90)).toString(),
                        "to", this.clock.instant().toString())));

        assertThat(errorCode(result)).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void omitsChatsOutsideTheProviderScopeFromTheListing() {
        Fixtures.ReadyChat chat = readyAndSynced("acme");
        McpCallContext chatgpt = new McpCallContext(chat.team().actor(), McpScopes.OPENAI_CHATGPT);

        assertThat(items(this.tools.listAllowedChats(chatgpt,
                new McpSchema.CallToolRequest("list_allowed_chats", Map.of())))).isEmpty();
    }

    @Test
    void allowsEveryProviderTheActivationExplicitlyCovers() {
        Fixtures.ReadyChat chat = this.fixtures.readyEngineeringChat("acme", this.clock.instant(),
                this.clock.instant().minus(Duration.ofDays(365)),
                this.clock.instant().plus(Duration.ofDays(365)), 365,
                McpScopes.ANTHROPIC_CLAUDE, McpScopes.OPENAI_CHATGPT);
        this.syncService.sync(chat.team().tenant().id(), chat.chat().id());

        for (String provider : List.of(McpScopes.ANTHROPIC_CLAUDE, McpScopes.OPENAI_CHATGPT)) {
            McpSchema.CallToolResult result = this.tools.listAllowedChats(
                    new McpCallContext(chat.team().actor(), provider),
                    new McpSchema.CallToolRequest("list_allowed_chats", Map.of()));
            assertThat(items(result)).as("provider %s", provider).hasSize(1);
        }
    }

    // --- approved window ----------------------------------------------------------------

    @Test
    void clampsARequestedRangeToTheApprovedWindow() {
        Instant approvedFrom = this.clock.instant().minus(Duration.ofDays(7));
        Fixtures.ReadyChat chat = this.fixtures.readyEngineeringChat("acme", this.clock.instant(),
                approvedFrom, this.clock.instant().plus(Duration.ofDays(7)), 365,
                McpScopes.ANTHROPIC_CLAUDE);
        this.syncService.sync(chat.team().tenant().id(), chat.chat().id());

        // The caller asks for 90 days; only the 7 approved days may be answered.
        McpSchema.CallToolResult result = history(chat.team(), chat.chat().id());
        Map<String, Object> effective = McpProtocolTests.asMap(
                McpProtocolTests.structured(result).get("effectiveRange"));

        assertThat(Instant.parse((String) effective.get("from"))).isEqualTo(approvedFrom);
    }

    @Test
    void clampsTheWindowToTheRetentionFloorWhenRetentionIsShorterThanTheApproval() {
        Fixtures.ReadyChat chat = this.fixtures.readyEngineeringChat("acme", this.clock.instant(),
                this.clock.instant().minus(Duration.ofDays(365)),
                this.clock.instant().plus(Duration.ofDays(365)), 10, McpScopes.ANTHROPIC_CLAUDE);
        this.syncService.sync(chat.team().tenant().id(), chat.chat().id());

        Map<String, Object> effective = McpProtocolTests.asMap(
                McpProtocolTests.structured(history(chat.team(), chat.chat().id())).get("effectiveRange"));

        assertThat(Instant.parse((String) effective.get("from")))
                .isEqualTo(this.clock.instant().minus(Duration.ofDays(10)));
    }

    @Test
    void searchAcrossChatsWithNonOverlappingApprovedWindowsReturnsEachChatsOwnResults() {
        // Security-audit finding F14: chat A is approved only for an older window, chat B
        // only for a recent one, with a one-day gap between them -- their intersection is
        // empty. The old code intersected every requested chat's window into one shared
        // range before searching, so a call spanning both chats returned nothing at all for
        // either. Each chat's own results must come back instead.
        Instant now = this.clock.instant();
        Fixtures.Team team = this.fixtures.team("f14");

        Chat chatA = this.fixtures.chat(team, SyntheticDataset.ENGINEERING_CHAT,
                "[SYNTHETIC DEMO] Orbital Widgets — Engineering", null);
        this.fixtures.participants(team, chatA, Fixtures.ENGINEERING_MEMBERS);
        ChatActivation activationA = this.fixtures.activation(team, chatA, ActivationState.ACTIVE,
                now.minus(Duration.ofDays(25)), now.minus(Duration.ofDays(15)), 3650,
                McpScopes.ANTHROPIC_CLAUDE);
        this.fixtures.consentAll(team, chatA, activationA, now.minus(Duration.ofDays(30)),
                now.plus(Duration.ofDays(300)));

        Chat chatB = this.fixtures.chat(team, SyntheticDataset.LAUNCH_OPS_CHAT,
                "[SYNTHETIC DEMO] Orbital Widgets — Launch Ops", "synthetic_launch_ops");
        this.fixtures.participants(team, chatB, Fixtures.LAUNCH_OPS_MEMBERS);
        ChatActivation activationB = this.fixtures.activation(team, chatB, ActivationState.ACTIVE,
                now.minus(Duration.ofDays(14)), now, 3650, McpScopes.ANTHROPIC_CLAUDE);
        this.fixtures.consentAll(team, chatB, activationB, now.minus(Duration.ofDays(30)),
                now.plus(Duration.ofDays(300)));

        this.syncService.sync(team.tenant().id(), chatA.id());
        this.syncService.sync(team.tenant().id(), chatB.id());

        // "backpressure" only appears in the engineering chat's script; "Thursday" only in
        // launch ops's -- a hit on either proves that chat's own content was actually reached.
        McpSchema.CallToolResult result = this.tools.searchMessages(contextFor(team),
                new McpSchema.CallToolRequest(McpToolNames.SEARCH_MESSAGES, Map.of(
                        "chat_ids", List.of(chatA.id().toString(), chatB.id().toString()),
                        "query", "backpressure OR Thursday",
                        "from", now.minus(Duration.ofDays(30)).toString(),
                        "to", now.toString(),
                        "limit", 50)));

        List<UUID> chatIdsInResults = items(result).stream()
                .map(item -> UUID.fromString((String) item.get("chatId")))
                .distinct()
                .toList();
        assertThat(chatIdsInResults)
                .as("each chat's own results must survive, not just one or neither")
                .containsExactlyInAnyOrder(chatA.id(), chatB.id());

        Map<String, Object> effectiveRangeByChat = asMap(structured(result).get("effectiveRangeByChat"));
        assertThat(effectiveRangeByChat).containsKeys(chatA.id().toString(), chatB.id().toString());
    }

    private Fixtures.ReadyChat readyAndSynced(String slug) {
        Fixtures.ReadyChat chat = this.fixtures.readyEngineeringChat(slug, this.clock.instant());
        this.syncService.sync(chat.team().tenant().id(), chat.chat().id());
        return chat;
    }
}
