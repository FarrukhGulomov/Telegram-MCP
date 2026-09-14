package io.tgmcp;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;
import io.tgmcp.config.AppProperties;
import io.tgmcp.mcp.McpCallContext;
import io.tgmcp.mcp.McpToolNames;
import io.tgmcp.mcp.McpTools;
import io.tgmcp.mcp.ToolResponses;
import io.tgmcp.ratelimit.RateLimiter;
import io.tgmcp.security.McpScopes;
import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import io.tgmcp.support.MutableClock;
import io.tgmcp.sync.ChatSyncService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;

import static io.tgmcp.McpProtocolTests.asMap;
import static io.tgmcp.McpProtocolTests.errorCode;
import static io.tgmcp.McpProtocolTests.items;
import static io.tgmcp.McpProtocolTests.structured;
import static org.assertj.core.api.Assertions.assertThat;

/** Per-caller rate limiting, and the hard byte cap on every response. */
class RateLimitAndResponseCapTests extends AbstractIntegrationTest {

    @Autowired
    private McpTools tools;

    @Autowired
    private ChatSyncService syncService;

    // --- rate limiting -------------------------------------------------------------------

    @Test
    void refillsTheBucketOverTimeAndKeepsCallersIndependent() {
        MutableClock clock = new MutableClock(java.time.Instant.parse("2026-06-15T12:00:00Z"));
        RateLimiter limiter = new RateLimiter(propertiesWithLimit(60), clock);

        for (int i = 0; i < 60; i++) {
            assertThat(limiter.tryAcquire("tenant-a:user-1")).as("call %d", i).isTrue();
        }
        assertThat(limiter.tryAcquire("tenant-a:user-1")).as("bucket is empty").isFalse();

        // A different caller has its own budget.
        assertThat(limiter.tryAcquire("tenant-b:user-9")).isTrue();

        // 60 per minute means one token per second.
        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.tryAcquire("tenant-a:user-1")).isTrue();
        assertThat(limiter.tryAcquire("tenant-a:user-1")).isFalse();
    }

    @Test
    void returnsRateLimitedRatherThanFailingWhenACallerExceedsItsBudget() {
        Fixtures.ReadyChat chat = this.fixtures.readyEngineeringChat("acme", this.clock.instant());
        this.syncService.sync(chat.team().tenant().id(), chat.chat().id());
        McpCallContext context = new McpCallContext(chat.team().actor(), McpScopes.ANTHROPIC_CLAUDE);

        // The test profile allows 1000/minute; spend them, then check the failure mode.
        McpSchema.CallToolResult limited = null;
        for (int i = 0; i < 1100 && limited == null; i++) {
            McpSchema.CallToolResult result = this.tools.getSyncStatus(context,
                    new McpSchema.CallToolRequest(McpToolNames.GET_SYNC_STATUS,
                            Map.of("chat_id", chat.chat().id().toString())));
            if (Boolean.TRUE.equals(result.isError())) {
                limited = result;
            }
        }

        assertThat(limited).as("the budget is finite").isNotNull();
        assertThat(errorCode(limited)).isEqualTo("RATE_LIMITED");
    }

    // --- response byte cap -----------------------------------------------------------------

    @Test
    void dropsItemsAndSaysSoWhenAPayloadWouldExceedTheByteCap() {
        ObjectMapper mapper = JsonMapper.builder().build();
        ToolResponses responses = new ToolResponses(mapper, 2048);

        List<Map<String, Object>> manyItems = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            manyItems.add(Map.of("messageId", String.valueOf(i), "text", "x".repeat(200)));
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("dataHandling", ToolResponses.DATA_HANDLING);
        payload.put("items", manyItems);

        McpSchema.CallToolResult result = responses.success(payload);

        Map<String, Object> body = McpProtocolTests.structured(result);
        Map<String, Object> truncation = asMap(body.get("truncation"));
        assertThat(truncation.get("truncated")).isEqualTo(true);
        assertThat(truncation.get("reason")).isEqualTo("RESPONSE_BYTE_CAP");
        assertThat((Integer) truncation.get("droppedItems")).isPositive();

        String encoded = ((McpSchema.TextContent) result.content().get(0)).text();
        assertThat(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(2048);
    }

    @Test
    void fittingCountReportsExactlyHowManyItemsFitTheBudget() {
        // Security-audit finding F15: this is the primitive a paginated tool must use to
        // recompute its cursor after trimming -- unlike success(), which trims but has no way
        // to rebuild a tool-specific cursor, so its own caller must know exactly how many
        // items survived.
        ObjectMapper mapper = JsonMapper.builder().build();
        ToolResponses responses = new ToolResponses(mapper, 2048);

        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            items.add(Map.of("messageId", String.valueOf(i), "text", "x".repeat(200)));
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("dataHandling", ToolResponses.DATA_HANDLING);

        int fitting = responses.fittingCount(payload, items);

        assertThat(fitting).isPositive().isLessThan(items.size());
        Map<String, Object> withOnlyFitting = new java.util.LinkedHashMap<>(payload);
        withOnlyFitting.put("items", items.subList(0, fitting));
        Map<String, Object> withOneMore = new java.util.LinkedHashMap<>(payload);
        withOneMore.put("items", items.subList(0, fitting + 1));
        assertThat(mapper.writeValueAsString(withOnlyFitting).getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(2048);
        assertThat(mapper.writeValueAsString(withOneMore).getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isGreaterThan(2048);
    }

    @Test
    void fittingCountReturnsTheFullSizeWhenEverythingAlreadyFits() {
        ToolResponses responses = new ToolResponses(JsonMapper.builder().build(), 262144);
        List<Map<String, Object>> items = List.of(Map.of("messageId", "1", "text", "short"));

        assertThat(responses.fittingCount(new java.util.LinkedHashMap<>(), items)).isEqualTo(1);
    }

    @Test
    void leavesASmallPayloadUntouched() {
        ToolResponses responses = new ToolResponses(JsonMapper.builder().build(), 262144);
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("items", List.of(Map.of("messageId", "1", "text", "short")));

        Map<String, Object> body = McpProtocolTests.structured(responses.success(payload));

        assertThat(body).doesNotContainKey("truncation");
        assertThat((List<?>) body.get("items")).hasSize(1);
    }

    @Test
    void truncatesAnOverlongMessageBodyAndFlagsIt() {
        Fixtures.ReadyChat chat = this.fixtures.readyEngineeringChat("acme", this.clock.instant());
        this.syncService.sync(chat.team().tenant().id(), chat.chat().id());
        this.jdbcTemplate.update("UPDATE messages SET content = ? WHERE chat_id = ?",
                "novel ".repeat(2000), chat.chat().id());

        McpSchema.CallToolResult result = this.tools.searchMessages(
                new McpCallContext(chat.team().actor(), McpScopes.ANTHROPIC_CLAUDE),
                new McpSchema.CallToolRequest(McpToolNames.SEARCH_MESSAGES, Map.of(
                        "chat_ids", List.of(chat.chat().id().toString()),
                        "query", "novel",
                        "from", this.clock.instant().minus(Duration.ofDays(90)).toString(),
                        "to", this.clock.instant().toString(),
                        "limit", 1)));

        Map<String, Object> item = items(result).get(0);
        assertThat(item.get("textTruncated")).isEqualTo(true);
        assertThat((String) item.get("text")).hasSize(4000);
    }

    @Test
    void everySuccessfulResponseCarriesFreshnessAndCoverageInformation() {
        Fixtures.ReadyChat chat = this.fixtures.readyEngineeringChat("acme", this.clock.instant());
        this.syncService.sync(chat.team().tenant().id(), chat.chat().id());

        McpSchema.CallToolResult listing = this.tools.listAllowedChats(
                new McpCallContext(chat.team().actor(), McpScopes.ANTHROPIC_CLAUDE),
                new McpSchema.CallToolRequest(McpToolNames.LIST_ALLOWED_CHATS, Map.of()));

        Map<String, Object> item = items(listing).get(0);
        assertThat(asMap(item.get("coverage"))).containsKeys("approvedFrom", "approvedTo",
                "retentionDays", "storedMessageCount", "backfillComplete", "partialHistory", "note");
        assertThat(asMap(item.get("freshness"))).containsKeys("lastSuccessfulSyncAt",
                "stalenessSeconds", "everSynced");
        assertThat(structured(listing)).containsKey("generatedAt");
    }

    private static AppProperties propertiesWithLimit(int perMinute) {
        return new AppProperties(
                new AppProperties.Telegram(io.tgmcp.telegram.gateway.TelegramAdapterKind.MOCK,
                        null, null, "./tdlib", false),
                new AppProperties.Mcp(50, 200, 20, 512, 20, 262144, 4000, 366,
                        Duration.ofMinutes(15), perMinute, "http://localhost:8080/mcp", "mcp:read"),
                new AppProperties.Consent("2026-09-v1", true, Duration.ofDays(365)),
                new AppProperties.Sync(Duration.ofSeconds(30), 200, 5, 2, 6,
                        Duration.ofSeconds(5), Duration.ofMinutes(10), Duration.ofHours(1)),
                new AppProperties.Secrets(null, "test", null),
                new AppProperties.Demo(false, "demo", "Demo", "a@b.invalid", "pw", List.of()));
    }
}
