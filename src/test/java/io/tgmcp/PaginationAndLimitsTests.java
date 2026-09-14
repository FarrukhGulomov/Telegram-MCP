package io.tgmcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

import static io.tgmcp.McpProtocolTests.asMap;
import static io.tgmcp.McpProtocolTests.errorCode;
import static io.tgmcp.McpProtocolTests.items;
import static io.tgmcp.McpProtocolTests.structured;
import static org.assertj.core.api.Assertions.assertThat;

/** Pagination boundaries, cursor tampering, and the caps that bound every response. */
class PaginationAndLimitsTests extends AbstractIntegrationTest {

    @Autowired
    private McpTools tools;

    @Autowired
    private ChatSyncService syncService;

    private Fixtures.ReadyChat chat;

    private Fixtures.ReadyChat synced() {
        if (this.chat == null) {
            this.chat = this.fixtures.readyEngineeringChat("acme", this.clock.instant());
            this.syncService.sync(this.chat.team().tenant().id(), this.chat.chat().id());
        }
        return this.chat;
    }

    private McpCallContext context() {
        return new McpCallContext(synced().team().actor(), McpScopes.ANTHROPIC_CLAUDE);
    }

    private McpSchema.CallToolResult history(Map<String, Object> extra) {
        Map<String, Object> args = new java.util.LinkedHashMap<>(Map.of(
                "chat_id", synced().chat().id().toString(),
                "from", this.clock.instant().minus(Duration.ofDays(90)).toString(),
                "to", this.clock.instant().toString()));
        args.putAll(extra);
        return this.tools.getChatHistory(context(),
                new McpSchema.CallToolRequest(McpToolNames.GET_CHAT_HISTORY, args));
    }

    private static String cursor(McpSchema.CallToolResult result) {
        return (String) asMap(structured(result).get("pagination")).get("cursor");
    }

    private static boolean hasMore(McpSchema.CallToolResult result) {
        return (Boolean) asMap(structured(result).get("pagination")).get("hasMore");
    }

    // --- paging -------------------------------------------------------------------------

    @Test
    void walksTheWholeHistoryExactlyOnceAcrossPages() {
        List<String> seen = new ArrayList<>();
        McpSchema.CallToolResult page = history(Map.of("limit", 7));
        int guard = 0;

        while (true) {
            items(page).forEach(item -> seen.add((String) item.get("messageId")));
            if (!hasMore(page)) {
                break;
            }
            assertThat(++guard).as("pagination must terminate").isLessThan(50);
            page = history(Map.of("limit", 7, "cursor", cursor(page)));
        }

        Set<String> unique = new HashSet<>(seen);
        assertThat(seen).as("no message is returned twice").hasSameSizeAs(unique);
        assertThat(seen).as("every synced message is reachable").hasSize(30);
    }

    @Test
    void reportsNoCursorOnTheLastPage() {
        McpSchema.CallToolResult onePage = history(Map.of("limit", 100));

        assertThat(hasMore(onePage)).isFalse();
        assertThat(cursor(onePage)).isNull();
    }

    @Test
    void returnsMessagesInChronologicalOrderWithinAPage() {
        List<Map<String, Object>> items = items(history(Map.of("limit", 30)));

        List<String> timestamps = items.stream().map(item -> (String) item.get("sentAt")).toList();
        assertThat(timestamps).isSorted();
    }

    // --- cursor tampering ----------------------------------------------------------------

    @Test
    void rejectsATamperedCursor() {
        String valid = cursor(history(Map.of("limit", 5)));
        String tampered = valid.substring(0, valid.length() - 3) + "AAA";

        McpSchema.CallToolResult result = history(Map.of("limit", 5, "cursor", tampered));

        assertThat(errorCode(result)).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void rejectsACursorFromADifferentPageSizeRequestOnlyWhenItsBindingChanges() {
        // Page size is not part of the binding, so re-using a cursor with a different limit is
        // legitimate; changing the *range* is not.
        String valid = cursor(history(Map.of("limit", 5)));

        assertThat(history(Map.of("limit", 9, "cursor", valid)).isError()).isFalse();

        McpSchema.CallToolResult movedRange = this.tools.getChatHistory(context(),
                new McpSchema.CallToolRequest(McpToolNames.GET_CHAT_HISTORY, Map.of(
                        "chat_id", synced().chat().id().toString(),
                        "from", this.clock.instant().minus(Duration.ofDays(80)).toString(),
                        "to", this.clock.instant().toString(),
                        "cursor", valid)));
        assertThat(errorCode(movedRange)).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void rejectsASearchCursorReplayedAgainstADifferentQuery() {
        McpSchema.CallToolResult first = this.tools.searchMessages(context(),
                new McpSchema.CallToolRequest(McpToolNames.SEARCH_MESSAGES, Map.of(
                        "chat_ids", List.of(synced().chat().id().toString()),
                        "query", "telemetry",
                        "from", this.clock.instant().minus(Duration.ofDays(90)).toString(),
                        "to", this.clock.instant().toString(),
                        "limit", 1)));
        String valid = cursor(first);
        assertThat(valid).isNotNull();

        McpSchema.CallToolResult replay = this.tools.searchMessages(context(),
                new McpSchema.CallToolRequest(McpToolNames.SEARCH_MESSAGES, Map.of(
                        "chat_ids", List.of(synced().chat().id().toString()),
                        "query", "retention",
                        "from", this.clock.instant().minus(Duration.ofDays(90)).toString(),
                        "to", this.clock.instant().toString(),
                        "cursor", valid)));

        assertThat(errorCode(replay)).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void rejectsAnotherTenantsCursor() {
        String valid = cursor(history(Map.of("limit", 5)));
        Fixtures.ReadyChat other = this.fixtures.readyEngineeringChat("globex", this.clock.instant());
        this.syncService.sync(other.team().tenant().id(), other.chat().id());

        McpSchema.CallToolResult result = this.tools.getChatHistory(
                new McpCallContext(other.team().actor(), McpScopes.ANTHROPIC_CLAUDE),
                new McpSchema.CallToolRequest(McpToolNames.GET_CHAT_HISTORY, Map.of(
                        "chat_id", other.chat().id().toString(),
                        "from", this.clock.instant().minus(Duration.ofDays(90)).toString(),
                        "to", this.clock.instant().toString(),
                        "cursor", valid)));

        assertThat(errorCode(result)).isEqualTo("INVALID_ARGUMENT");
    }

    // --- input caps -----------------------------------------------------------------------

    @Test
    void rejectsAPageSizeAboveTheCap() {
        assertThat(errorCode(history(Map.of("limit", 5000)))).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void rejectsAMissingOrUnboundedDateRange() {
        McpSchema.CallToolResult noRange = this.tools.getChatHistory(context(),
                new McpSchema.CallToolRequest(McpToolNames.GET_CHAT_HISTORY,
                        Map.of("chat_id", synced().chat().id().toString())));

        assertThat(errorCode(noRange)).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void rejectsARangeLongerThanTheConfiguredMaximum() {
        McpSchema.CallToolResult tooWide = this.tools.getChatHistory(context(),
                new McpSchema.CallToolRequest(McpToolNames.GET_CHAT_HISTORY, Map.of(
                        "chat_id", synced().chat().id().toString(),
                        "from", this.clock.instant().minus(Duration.ofDays(4000)).toString(),
                        "to", this.clock.instant().toString())));

        assertThat(errorCode(tooWide)).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void rejectsAnInvertedRange() {
        McpSchema.CallToolResult inverted = this.tools.getChatHistory(context(),
                new McpSchema.CallToolRequest(McpToolNames.GET_CHAT_HISTORY, Map.of(
                        "chat_id", synced().chat().id().toString(),
                        "from", this.clock.instant().toString(),
                        "to", this.clock.instant().minus(Duration.ofDays(1)).toString())));

        assertThat(errorCode(inverted)).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void rejectsAnOverLongQuery() {
        McpSchema.CallToolResult result = this.tools.searchMessages(context(),
                new McpSchema.CallToolRequest(McpToolNames.SEARCH_MESSAGES, Map.of(
                        "chat_ids", List.of(synced().chat().id().toString()),
                        "query", "x".repeat(5000),
                        "from", this.clock.instant().minus(Duration.ofDays(90)).toString(),
                        "to", this.clock.instant().toString())));

        assertThat(errorCode(result)).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void rejectsTooManyChatsInOneSearch() {
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tooMany.add(UUID.randomUUID().toString());
        }

        McpSchema.CallToolResult result = this.tools.searchMessages(context(),
                new McpSchema.CallToolRequest(McpToolNames.SEARCH_MESSAGES, Map.of(
                        "chat_ids", tooMany,
                        "query", "anything",
                        "from", this.clock.instant().minus(Duration.ofDays(90)).toString(),
                        "to", this.clock.instant().toString())));

        assertThat(errorCode(result)).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void rejectsUnknownArgumentsRatherThanIgnoringThem() {
        McpSchema.CallToolResult result = history(Map.of("tenant_id", UUID.randomUUID().toString()));

        // Accepting a tenant_id argument would be exactly the confusion this rejects.
        assertThat(errorCode(result)).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void capsTheContextWindowSize() {
        McpSchema.CallToolResult result = this.tools.getMessageContext(context(),
                new McpSchema.CallToolRequest(McpToolNames.GET_MESSAGE_CONTEXT, Map.of(
                        "chat_id", synced().chat().id().toString(),
                        "message_id", "1000",
                        "before", 5000, "after", 5000)));

        assertThat(errorCode(result)).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void reportsAMissingOrOutOfWindowMessageAsAccessDenied() {
        McpSchema.CallToolResult result = this.tools.getMessageContext(context(),
                new McpSchema.CallToolRequest(McpToolNames.GET_MESSAGE_CONTEXT, Map.of(
                        "chat_id", synced().chat().id().toString(),
                        "message_id", "999999999")));

        assertThat(errorCode(result)).isEqualTo("ACCESS_DENIED");
    }
}
