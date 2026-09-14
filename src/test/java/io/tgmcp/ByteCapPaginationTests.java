package io.tgmcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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

import static io.tgmcp.McpProtocolTests.asMap;
import static io.tgmcp.McpProtocolTests.items;
import static io.tgmcp.McpProtocolTests.structured;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security-audit finding F15: a page the byte cap cut short used to leave its pagination
 * cursor pointing at whatever the full, untrimmed database page had already computed --
 * strictly past the dropped tail, since that cursor came from the last row of the *original*
 * page, not the last row actually returned. Following it therefore skipped every message
 * between the two, permanently: they were still in the database, but no cursor could ever
 * reach them again.
 *
 * <p>Runs against a deliberately tiny {@code tgmcp.mcp.max-response-bytes} so a single chat's
 * ordinary synthetic history reliably exceeds it, forcing the byte cap to actually engage
 * rather than merely being exercised in isolation.
 */
@TestPropertySource(properties = "tgmcp.mcp.max-response-bytes=3072")
class ByteCapPaginationTests extends AbstractIntegrationTest {

    @Autowired
    private McpTools tools;

    @Autowired
    private ChatSyncService syncService;

    @Test
    void payingOutTheCursorAcrossByteCappedPagesCoversEveryMessageExactlyOnce() {
        Fixtures.ReadyChat chat = this.fixtures.readyEngineeringChat("f15", this.clock.instant());
        this.syncService.sync(chat.team().tenant().id(), chat.chat().id());
        McpCallContext context = new McpCallContext(chat.team().actor(), McpScopes.ANTHROPIC_CLAUDE);

        List<String> seenMessageIds = new ArrayList<>();
        boolean sawByteCapTruncation = false;
        String cursor = null;
        int pages = 0;
        do {
            Map<String, Object> args = new java.util.LinkedHashMap<>(Map.of(
                    "chat_id", chat.chat().id().toString(),
                    "from", this.clock.instant().minus(Duration.ofDays(30)).toString(),
                    "to", this.clock.instant().toString(),
                    "limit", 50));
            if (cursor != null) {
                args.put("cursor", cursor);
            }
            McpSchema.CallToolResult result = this.tools.getChatHistory(context,
                    new McpSchema.CallToolRequest(McpToolNames.GET_CHAT_HISTORY, args));

            assertThat(result.isError())
                    .as("page %d must succeed, not report the page too large to answer at all", pages)
                    .isFalse();

            for (Map<String, Object> item : items(result)) {
                seenMessageIds.add((String) item.get("messageId"));
            }
            Map<String, Object> truncation = asMap(structured(result).get("truncation"));
            if (Boolean.TRUE.equals(truncation.get("truncated"))) {
                assertThat(truncation.get("reason")).isEqualTo("RESPONSE_BYTE_CAP");
                sawByteCapTruncation = true;
            }
            Map<String, Object> pagination = asMap(structured(result).get("pagination"));
            boolean hasMore = Boolean.TRUE.equals(pagination.get("hasMore"));
            cursor = hasMore ? (String) pagination.get("cursor") : null;
            pages++;
        }
        while (cursor != null && pages < 50);

        assertThat(sawByteCapTruncation)
                .as("the tiny byte cap must actually have bitten at least once for this test to prove anything")
                .isTrue();
        assertThat(pages).as("the byte cap forced this into more than one page").isGreaterThan(1);
        assertThat(seenMessageIds)
                .as("every one of the chat's 30 synthetic messages must appear exactly once across all pages")
                .doesNotHaveDuplicates()
                .hasSize(30);
    }
}
