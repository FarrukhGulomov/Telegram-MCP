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
import io.tgmcp.mcp.McpCallContext;
import io.tgmcp.mcp.McpToolNames;
import io.tgmcp.mcp.McpTools;
import io.tgmcp.security.McpScopes;
import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import io.tgmcp.sync.ChatSyncService;
import io.tgmcp.telegram.mock.SyntheticDataset;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security-audit finding F20 (second half): {@code McpTools.guarded} used to be called with a
 * fixed {@code chatId} of {@code null} at every one of its five call sites -- decided before
 * any request argument had even been parsed -- so every audit row it wrote (allowed, denied,
 * rate-limited) for every MCP tool was unattributed to any chat, and a multi-chat {@link
 * McpToolNames#SEARCH_MESSAGES} call left no trace of which chats it actually touched. {@code
 * guarded} now takes a callback the tool body invokes with the chat id(s) it parsed from its
 * own arguments, as soon as they are known and before anything that can throw runs.
 */
class McpAuditAttributionTests extends AbstractIntegrationTest {

    @Autowired
    private McpTools tools;

    @Autowired
    private ChatSyncService syncService;

    private McpCallContext contextFor(Fixtures.Team team) {
        return new McpCallContext(team.actor(), McpScopes.ANTHROPIC_CLAUDE);
    }

    private List<UUID> auditedChatIds(UUID tenantId, String action) {
        return this.jdbcTemplate.queryForList(
                "SELECT chat_id FROM audit_events WHERE tenant_id = ? AND action = ?",
                UUID.class, tenantId, action);
    }

    @Test
    void getChatHistoryAttributesItsAuditRowToTheRequestedChatRatherThanLeavingItUnattributed() {
        Fixtures.ReadyChat chat = this.fixtures.readyEngineeringChat("f20-history", this.clock.instant());
        this.syncService.sync(chat.team().tenant().id(), chat.chat().id());

        McpSchema.CallToolResult result = this.tools.getChatHistory(contextFor(chat.team()),
                new McpSchema.CallToolRequest(McpToolNames.GET_CHAT_HISTORY, Map.of(
                        "chat_id", chat.chat().id().toString(),
                        "from", this.clock.instant().minus(Duration.ofDays(30)).toString(),
                        "to", this.clock.instant().toString())));
        assertThat(result.isError()).isFalse();

        assertThat(auditedChatIds(chat.team().tenant().id(), "MCP_TOOL_CALL"))
                .as("must name the chat that was actually read, not leave the row unattributed")
                .containsExactly(chat.chat().id());
    }

    @Test
    void aDeniedChatHistoryCallIsStillAttributedToTheChatThatWasDenied() {
        Fixtures.ReadyChat chat = this.fixtures.readyEngineeringChat("f20-denied", this.clock.instant());
        UUID unauthorizedChatId = UUID.randomUUID();

        McpSchema.CallToolResult result = this.tools.getChatHistory(contextFor(chat.team()),
                new McpSchema.CallToolRequest(McpToolNames.GET_CHAT_HISTORY, Map.of(
                        "chat_id", unauthorizedChatId.toString(),
                        "from", this.clock.instant().minus(Duration.ofDays(30)).toString(),
                        "to", this.clock.instant().toString())));
        assertThat(result.isError()).isTrue();

        // Attribution must reflect what was *requested*, since access-denied is exactly the
        // outcome where knowing which chat was probed matters most.
        assertThat(auditedChatIds(chat.team().tenant().id(), "MCP_ACCESS_DENIED"))
                .containsExactly(unauthorizedChatId);
    }

    @Test
    void searchMessagesAcrossMultipleChatsRecordsOneAuditRowPerChatRatherThanOneUnattributedRow() {
        Instant now = this.clock.instant();
        Fixtures.Team team = this.fixtures.team("f20-search");

        Chat chatA = this.fixtures.chat(team, SyntheticDataset.ENGINEERING_CHAT,
                "[SYNTHETIC DEMO] Orbital Widgets — Engineering", null);
        this.fixtures.participants(team, chatA, Fixtures.ENGINEERING_MEMBERS);
        ChatActivation activationA = this.fixtures.activation(team, chatA, ActivationState.ACTIVE,
                now.minus(Duration.ofDays(30)), now, 3650, McpScopes.ANTHROPIC_CLAUDE);
        this.fixtures.consentAll(team, chatA, activationA, now.minus(Duration.ofDays(30)),
                now.plus(Duration.ofDays(300)));

        Chat chatB = this.fixtures.chat(team, SyntheticDataset.LAUNCH_OPS_CHAT,
                "[SYNTHETIC DEMO] Orbital Widgets — Launch Ops", "synthetic_launch_ops");
        this.fixtures.participants(team, chatB, Fixtures.LAUNCH_OPS_MEMBERS);
        ChatActivation activationB = this.fixtures.activation(team, chatB, ActivationState.ACTIVE,
                now.minus(Duration.ofDays(30)), now, 3650, McpScopes.ANTHROPIC_CLAUDE);
        this.fixtures.consentAll(team, chatB, activationB, now.minus(Duration.ofDays(30)),
                now.plus(Duration.ofDays(300)));

        this.syncService.sync(team.tenant().id(), chatA.id());
        this.syncService.sync(team.tenant().id(), chatB.id());

        McpSchema.CallToolResult result = this.tools.searchMessages(contextFor(team),
                new McpSchema.CallToolRequest(McpToolNames.SEARCH_MESSAGES, Map.of(
                        "chat_ids", List.of(chatA.id().toString(), chatB.id().toString()),
                        "query", "backpressure OR Thursday",
                        "from", now.minus(Duration.ofDays(30)).toString(),
                        "to", now.toString())));
        assertThat(result.isError()).isFalse();

        assertThat(auditedChatIds(team.tenant().id(), "MCP_TOOL_CALL"))
                .as("a multi-chat call must leave one attributed row per chat it touched")
                .containsExactlyInAnyOrder(chatA.id(), chatB.id());
    }
}
