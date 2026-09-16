package io.tgmcp;

import java.time.Duration;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;
import io.tgmcp.mcp.McpCallContext;
import io.tgmcp.mcp.McpToolNames;
import io.tgmcp.mcp.McpTools;
import io.tgmcp.security.McpScopes;
import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import io.tgmcp.sync.ChatSyncService;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security-audit finding SEC-15: {@code McpTools.guarded} used to log {@code ALLOWED}
 * unconditionally whenever the tool body returned normally -- even when the returned {@link
 * McpSchema.CallToolResult} was itself a structured error (see {@link
 * io.tgmcp.mcp.ToolResponses#error}), since nothing inspected {@code result.isError()}. Runs
 * against a deliberately tiny {@code tgmcp.mcp.max-response-bytes} so a single ordinary
 * synthetic message reliably cannot fit at all, forcing {@code McpTools} to return that
 * structured error (INVALID_ARGUMENT) without throwing.
 */
@TestPropertySource(properties = "tgmcp.mcp.max-response-bytes=200")
class McpToolErrorOutcomeAuditTests extends AbstractIntegrationTest {

    @Autowired
    private McpTools tools;

    @Autowired
    private ChatSyncService syncService;

    @Test
    void aStructuredErrorResultIsRecordedAsErrorRatherThanAllowed() {
        Fixtures.ReadyChat chat = this.fixtures.readyEngineeringChat("sec15-error", this.clock.instant());
        this.syncService.sync(chat.team().tenant().id(), chat.chat().id());
        McpCallContext context = new McpCallContext(chat.team().actor(), McpScopes.ANTHROPIC_CLAUDE);

        McpSchema.CallToolResult result = this.tools.getChatHistory(context,
                new McpSchema.CallToolRequest(McpToolNames.GET_CHAT_HISTORY, Map.of(
                        "chat_id", chat.chat().id().toString(),
                        "from", this.clock.instant().minus(Duration.ofDays(30)).toString(),
                        "to", this.clock.instant().toString())));

        assertThat(result.isError())
                .as("a 200-byte cap cannot fit even one ordinary synthetic message alongside the envelope")
                .isTrue();

        java.util.List<String> outcomes = this.jdbcTemplate.queryForList(
                "SELECT outcome FROM audit_events WHERE tenant_id = ? AND chat_id = ? AND action = 'MCP_TOOL_CALL'",
                String.class, chat.team().tenant().id(), chat.chat().id());
        assertThat(outcomes)
                .as("a returned error result must never be recorded as ALLOWED")
                .containsExactly("ERROR");
    }
}
