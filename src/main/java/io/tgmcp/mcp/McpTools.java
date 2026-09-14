package io.tgmcp.mcp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.modelcontextprotocol.spec.McpSchema;
import io.tgmcp.access.AccessErrorCode;
import io.tgmcp.access.AuthorizedChat;
import io.tgmcp.access.ChatAccessException;
import io.tgmcp.access.ChatAccessService;
import io.tgmcp.audit.AuditAction;
import io.tgmcp.audit.AuditService;
import io.tgmcp.chat.Chat;
import io.tgmcp.config.AppProperties;
import io.tgmcp.message.ChatCoverage;
import io.tgmcp.message.MessagePageKey;
import io.tgmcp.message.MessageRepository;
import io.tgmcp.message.MessageSlice;
import io.tgmcp.message.StoredMessage;
import io.tgmcp.ratelimit.RateLimiter;
import io.tgmcp.sync.SyncCheckpoint;
import io.tgmcp.tenancy.AuthenticatedActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

/**
 * Implementation of the five MCP tools.
 *
 * <p>Each tool follows the same shape: validate arguments, authorize through
 * {@link ChatAccessService}, read, re-authorize, then serialise. The second authorization is
 * not redundant — it is what makes a revocation that lands mid-request still deny the
 * response.
 */
@Service
public class McpTools {

    private static final Logger log = LoggerFactory.getLogger(McpTools.class);

    private final ChatAccessService accessService;
    private final MessageRepository messageRepository;
    private final CursorCodec cursorCodec;
    private final RateLimiter rateLimiter;
    private final AuditService auditService;
    private final ToolResponses responses;
    private final AppProperties.Mcp limits;
    private final Clock clock;

    public McpTools(ChatAccessService accessService,
                    MessageRepository messageRepository,
                    CursorCodec cursorCodec,
                    RateLimiter rateLimiter,
                    AuditService auditService,
                    ToolResponses responses,
                    AppProperties properties,
                    Clock clock) {
        this.accessService = accessService;
        this.messageRepository = messageRepository;
        this.cursorCodec = cursorCodec;
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
        this.responses = responses;
        this.limits = properties.mcp();
        this.clock = clock;
    }

    // --- tools -------------------------------------------------------------------------

    public McpSchema.CallToolResult listAllowedChats(McpCallContext context, McpSchema.CallToolRequest request) {
        return guarded(context, McpToolNames.LIST_ALLOWED_CHATS, null, () -> {
            new ToolArguments(request.arguments()).rejectUnknown(List.of());
            Instant now = this.clock.instant();

            List<Map<String, Object>> items = new ArrayList<>();
            for (AuthorizedChat allowed : this.accessService.listAllowed(context.actor(), context.provider())) {
                items.add(chatSummary(allowed, now));
            }
            Map<String, Object> payload = envelope();
            payload.put("items", items);
            payload.put("provider", context.provider());
            payload.put("notice", "Only chats that were explicitly activated and whose participants all have "
                    + "current consent for this provider are listed. Other chats are omitted entirely.");
            return this.responses.success(payload);
        });
    }

    public McpSchema.CallToolResult searchMessages(McpCallContext context, McpSchema.CallToolRequest request) {
        // Parsing happens inside guarded() so that an argument error becomes a structured
        // INVALID_ARGUMENT result rather than escaping as a protocol-level exception.
        return guarded(context, McpToolNames.SEARCH_MESSAGES, null, () -> {
            ToolArguments args = new ToolArguments(request.arguments());
            args.rejectUnknown(List.of("chat_ids", "query", "from", "to", "cursor", "limit"));

            List<UUID> chatIds = args.requiredUuidList("chat_ids", this.limits.maxChatIdsPerQuery());
            String query = args.requiredText("query", this.limits.maxQueryLength());
            Instant from = args.requiredInstant("from");
            Instant to = args.requiredInstant("to");
            ToolArguments.validateRange(from, to, this.limits.maxRangeDays());
            int limit = args.optionalInt("limit", this.limits.defaultPageSize(), 1,
                    this.limits.maxPageSize());
            String cursor = args.optionalText("cursor", 2048);

            Instant now = this.clock.instant();
            Map<UUID, AuthorizedChat> authorized =
                    this.accessService.authorizeAll(context.actor(), chatIds, context.provider(), true);

            String scopeHash = CursorCodec.scopeHash(chatIds);
            String queryHash = CursorCodec.queryHash(query);
            MessagePageKey after = null;
            if (cursor != null) {
                after = this.cursorCodec.toPageKey(this.cursorCodec.decode(cursor,
                        McpToolNames.SEARCH_MESSAGES, context.actor(), scopeHash, queryHash, from, to));
            }

            // Clamp the caller's range to what each chat actually authorises.
            Instant windowStart = from;
            Instant windowEnd = to;
            for (AuthorizedChat allowed : authorized.values()) {
                windowStart = latest(windowStart, allowed.windowStart(now));
                windowEnd = earliest(windowEnd, allowed.windowEnd(now));
            }
            if (!windowEnd.isAfter(windowStart)) {
                Map<String, Object> empty = envelope();
                empty.put("items", List.of());
                empty.put("pagination", pagination(null, false, limit));
                empty.put("coverageByChat", coverageByChat(authorized, now));
                empty.put("notice", "The requested range lies outside every approved window.");
                return this.responses.success(empty);
            }

            MessageSlice slice = this.messageRepository.search(context.actor().tenantId(), chatIds, query,
                    windowStart, windowEnd, after, limit);

            // Authorization is re-checked after the read and before anything is emitted.
            this.accessService.reverify(context.actor(), chatIds, context.provider());

            List<Map<String, Object>> items = new ArrayList<>();
            for (StoredMessage message : slice.messages()) {
                AuthorizedChat owner = authorized.get(message.chatId());
                items.add(messageItem(message, owner == null ? null : owner.chat()));
            }

            String nextCursor = slice.nextKey() == null ? null
                    : this.cursorCodec.encode(McpToolNames.SEARCH_MESSAGES, context.actor(), scopeHash,
                            queryHash, from, to, slice.nextKey());

            Map<String, Object> payload = envelope();
            payload.put("items", items);
            payload.put("pagination", pagination(nextCursor, slice.hasMore(), limit));
            payload.put("query", Map.of("text", query, "matching", "PostgreSQL websearch_to_tsquery, english"));
            payload.put("requestedRange", range(from, to));
            payload.put("effectiveRange", range(windowStart, windowEnd));
            payload.put("coverageByChat", coverageByChat(authorized, now));
            payload.put("truncation", ToolResponses.truncation(false, null, 0));
            return this.responses.success(payload);
        });
    }

    public McpSchema.CallToolResult getChatHistory(McpCallContext context, McpSchema.CallToolRequest request) {
        return guarded(context, McpToolNames.GET_CHAT_HISTORY, null, () -> {
            ToolArguments args = new ToolArguments(request.arguments());
            args.rejectUnknown(List.of("chat_id", "from", "to", "cursor", "limit"));

            UUID chatId = args.requiredUuid("chat_id");
            Instant from = args.requiredInstant("from");
            Instant to = args.requiredInstant("to");
            ToolArguments.validateRange(from, to, this.limits.maxRangeDays());
            int limit = args.optionalInt("limit", this.limits.defaultPageSize(), 1,
                    this.limits.maxPageSize());
            String cursor = args.optionalText("cursor", 2048);

            Instant now = this.clock.instant();
            AuthorizedChat allowed = this.accessService.authorize(context.actor(), chatId,
                    context.provider(), true);

            String scopeHash = CursorCodec.scopeHash(List.of(chatId));
            String queryHash = CursorCodec.queryHash("");
            MessagePageKey after = null;
            if (cursor != null) {
                after = this.cursorCodec.toPageKey(this.cursorCodec.decode(cursor,
                        McpToolNames.GET_CHAT_HISTORY, context.actor(), scopeHash, queryHash, from, to));
            }

            Instant windowStart = latest(from, allowed.windowStart(now));
            Instant windowEnd = earliest(to, allowed.windowEnd(now));
            if (!windowEnd.isAfter(windowStart)) {
                Map<String, Object> empty = envelope();
                empty.put("items", List.of());
                empty.put("pagination", pagination(null, false, limit));
                empty.put("coverage", coverage(allowed, now));
                empty.put("notice", "The requested range lies outside the approved window for this chat.");
                return this.responses.success(empty);
            }

            MessageSlice slice = this.messageRepository.history(context.actor().tenantId(), chatId,
                    windowStart, windowEnd, after, limit);
            this.accessService.reverify(context.actor(), List.of(chatId), context.provider());

            List<Map<String, Object>> items = slice.messages().stream()
                    .map(message -> messageItem(message, allowed.chat()))
                    .toList();
            String nextCursor = slice.nextKey() == null ? null
                    : this.cursorCodec.encode(McpToolNames.GET_CHAT_HISTORY, context.actor(), scopeHash,
                            queryHash, from, to, slice.nextKey());

            Map<String, Object> payload = envelope();
            payload.put("items", items);
            payload.put("pagination", pagination(nextCursor, slice.hasMore(), limit));
            payload.put("requestedRange", range(from, to));
            payload.put("effectiveRange", range(windowStart, windowEnd));
            payload.put("coverage", coverage(allowed, now));
            payload.put("truncation", ToolResponses.truncation(false, null, 0));
            return this.responses.success(payload);
        });
    }

    public McpSchema.CallToolResult getMessageContext(McpCallContext context, McpSchema.CallToolRequest request) {
        return guarded(context, McpToolNames.GET_MESSAGE_CONTEXT, null, () -> {
            ToolArguments args = new ToolArguments(request.arguments());
            args.rejectUnknown(List.of("chat_id", "message_id", "before", "after"));

            UUID chatId = args.requiredUuid("chat_id");
            long messageId = args.requiredLong("message_id");
            int before = args.optionalInt("before", 5, 0, this.limits.maxContextMessages());
            int after = args.optionalInt("after", 5, 0, this.limits.maxContextMessages());

            Instant now = this.clock.instant();
            AuthorizedChat allowed = this.accessService.authorize(context.actor(), chatId,
                    context.provider(), true);
            Instant windowStart = allowed.windowStart(now);
            Instant windowEnd = allowed.windowEnd(now);

            StoredMessage pivot = this.messageRepository.find(context.actor().tenantId(), chatId, messageId)
                    // A message that is absent, deleted, or outside the approved window is
                    // reported the same way, so the caller learns nothing from the difference.
                    .filter(m -> !m.sentAt().isBefore(windowStart) && !m.sentAt().isAfter(windowEnd))
                    .orElseThrow(ChatAccessException::denied);

            List<StoredMessage> preceding = this.messageRepository.before(context.actor().tenantId(), chatId,
                    pivot.sentAt(), pivot.tgMessageId(), windowStart, before);
            List<StoredMessage> following = this.messageRepository.after(context.actor().tenantId(), chatId,
                    pivot.sentAt(), pivot.tgMessageId(), windowEnd, after);

            this.accessService.reverify(context.actor(), List.of(chatId), context.provider());

            List<Map<String, Object>> items = new ArrayList<>();
            preceding.forEach(m -> items.add(messageItem(m, allowed.chat())));
            Map<String, Object> pivotItem = messageItem(pivot, allowed.chat());
            pivotItem.put("isPivot", true);
            items.add(pivotItem);
            following.forEach(m -> items.add(messageItem(m, allowed.chat())));

            Map<String, Object> payload = envelope();
            payload.put("items", items);
            payload.put("pivotMessageId", String.valueOf(messageId));
            payload.put("requested", Map.of("before", before, "after", after));
            payload.put("returned", Map.of("before", preceding.size(), "after", following.size()));
            payload.put("coverage", coverage(allowed, now));
            payload.put("truncation", ToolResponses.truncation(
                    preceding.size() < before || following.size() < after,
                    "WINDOW_BOUNDARY", 0));
            return this.responses.success(payload);
        });
    }

    public McpSchema.CallToolResult getSyncStatus(McpCallContext context, McpSchema.CallToolRequest request) {
        return guarded(context, McpToolNames.GET_SYNC_STATUS, null, () -> {
            ToolArguments args = new ToolArguments(request.arguments());
            args.rejectUnknown(List.of("chat_id"));
            UUID chatId = args.requiredUuid("chat_id");

            Instant now = this.clock.instant();
            // requireSynced=false: reporting "not synced yet" is the point of this tool.
            AuthorizedChat allowed = this.accessService.authorize(context.actor(), chatId,
                    context.provider(), false);
            Map<String, Object> payload = envelope();
            payload.put("chatId", chatId.toString());
            payload.put("title", allowed.chat().title());
            payload.put("coverage", coverage(allowed, now));
            payload.put("freshness", freshness(allowed, now));
            payload.put("sync", syncDetail(allowed));
            return this.responses.success(payload);
        });
    }

    // --- shared plumbing ---------------------------------------------------------------

    private McpSchema.CallToolResult guarded(McpCallContext context, String tool, UUID chatId,
                                             java.util.function.Supplier<McpSchema.CallToolResult> body) {
        String rateKey = context.actor().tenantId() + ":" + context.actor().userId();
        if (!this.rateLimiter.tryAcquire(rateKey)) {
            this.auditService.record(context.actor(), AuditAction.MCP_RATE_LIMITED, chatId,
                    AuditService.DENIED, Map.of("tool", tool));
            return this.responses.error(AccessErrorCode.RATE_LIMITED,
                    "Too many requests. Retry shortly.");
        }
        try {
            McpSchema.CallToolResult result = body.get();
            this.auditService.record(context.actor(), AuditAction.MCP_TOOL_CALL, chatId,
                    AuditService.ALLOWED, Map.of("tool", tool, "provider", context.provider()));
            return result;
        }
        catch (ChatAccessException ex) {
            this.auditService.record(context.actor(), AuditAction.MCP_ACCESS_DENIED, chatId,
                    AuditService.DENIED, Map.of("tool", tool, "code", ex.code().name()));
            return this.responses.error(ex.code(), ex.getMessage());
        }
        catch (CursorException ex) {
            this.auditService.record(context.actor(), AuditAction.MCP_ACCESS_DENIED, chatId,
                    AuditService.DENIED, Map.of("tool", tool, "code", "INVALID_CURSOR"));
            return this.responses.error(AccessErrorCode.INVALID_ARGUMENT,
                    "The pagination cursor is not valid for this request. Start a new query.");
        }
        catch (RuntimeException ex) {
            // Never surface internal detail to an MCP client.
            log.error("MCP tool {} failed", tool, ex);
            return this.responses.error(AccessErrorCode.INTERNAL_ERROR,
                    "The request could not be completed.");
        }
    }

    private Map<String, Object> envelope() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dataHandling", ToolResponses.DATA_HANDLING);
        payload.put("generatedAt", this.clock.instant().toString());
        return payload;
    }

    private Map<String, Object> messageItem(StoredMessage message, Chat chat) {
        String text = message.content() == null ? "" : message.content();
        boolean textTruncated = text.length() > this.limits.maxMessageTextChars();
        if (textTruncated) {
            text = text.substring(0, this.limits.maxMessageTextChars());
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("chatId", message.chatId().toString());
        item.put("chatTitle", chat == null ? null : chat.title());
        // Telegram message ids exceed 2^53 in practice; send them as strings.
        item.put("messageId", String.valueOf(message.tgMessageId()));
        item.put("sentAt", message.sentAt().toString());
        item.put("editedAt", message.editedAt() == null ? null : message.editedAt().toString());
        item.put("sender", Map.of(
                "tgUserId", message.senderTgUserId() == null ? "" : String.valueOf(message.senderTgUserId()),
                "displayName", message.senderDisplayName() == null ? "" : message.senderDisplayName()));
        item.put("text", text);
        item.put("textTruncated", textTruncated);
        item.put("replyToMessageId", message.replyToTgMessageId() == null
                ? null : String.valueOf(message.replyToTgMessageId()));
        // Absent rather than invented: a private chat has no link anyone else can resolve.
        item.put("link", message.sourceLink());
        item.put("source", Map.of(
                "provider", "telegram",
                "chatType", chat == null ? "UNKNOWN" : chat.chatType(),
                "contentKind", "text"));
        return item;
    }

    private Map<String, Object> chatSummary(AuthorizedChat allowed, Instant now) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("chatId", allowed.chat().id().toString());
        item.put("title", allowed.chat().title());
        item.put("chatType", allowed.chat().chatType());
        item.put("purpose", allowed.activation().purpose());
        item.put("providerScope", List.copyOf(allowed.activation().providers()));
        item.put("consentVersion", allowed.activation().consentVersion());
        item.put("participantsRequiringConsent", allowed.coverage().requiredParticipants());
        item.put("coverage", coverage(allowed, now));
        item.put("freshness", freshness(allowed, now));
        return item;
    }

    private Map<String, Object> coverage(AuthorizedChat allowed, Instant now) {
        ChatCoverage stored = this.messageRepository.coverage(
                allowed.chat().tenantId(), allowed.chat().id());
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("approvedFrom", allowed.activation().approvedFrom().toString());
        map.put("approvedTo", allowed.activation().approvedTo().toString());
        map.put("retentionDays", allowed.activation().retentionDays());
        map.put("effectiveFrom", allowed.windowStart(now).toString());
        map.put("effectiveTo", allowed.windowEnd(now).toString());
        map.put("storedFrom", stored.earliestStored() == null ? null : stored.earliestStored().toString());
        map.put("storedTo", stored.latestStored() == null ? null : stored.latestStored().toString());
        map.put("storedMessageCount", stored.storedMessageCount());
        map.put("backfillComplete", !allowed.partialHistory());
        map.put("partialHistory", allowed.partialHistory());
        map.put("note", allowed.partialHistory()
                ? "History for this chat is incomplete: backfill has not finished, so older messages "
                        + "inside the approved window may be missing. Do not treat absence as evidence."
                : "Backfill reports complete for the approved window. Messages deleted in Telegram, or "
                        + "aged out by retention, are still absent by design.");
        return map;
    }

    private Map<String, Object> coverageByChat(Map<UUID, AuthorizedChat> authorized, Instant now) {
        Map<String, Object> map = new LinkedHashMap<>();
        authorized.forEach((chatId, allowed) -> map.put(chatId.toString(), coverage(allowed, now)));
        return map;
    }

    private Map<String, Object> freshness(AuthorizedChat allowed, Instant now) {
        Map<String, Object> map = new LinkedHashMap<>();
        Instant last = allowed.lastSuccessfulSync().orElse(null);
        map.put("lastSuccessfulSyncAt", last == null ? null : last.toString());
        map.put("stalenessSeconds", last == null ? null : Duration.between(last, now).toSeconds());
        map.put("everSynced", last != null);
        return map;
    }

    private Map<String, Object> syncDetail(AuthorizedChat allowed) {
        SyncCheckpoint checkpoint = allowed.checkpoint();
        Map<String, Object> map = new LinkedHashMap<>();
        if (checkpoint == null) {
            map.put("state", "NEVER_STARTED");
            map.put("backfillComplete", false);
            map.put("consecutiveFailures", 0);
            map.put("halted", false);
            map.put("lastErrorCode", null);
            return map;
        }
        map.put("state", checkpoint.halted() ? "HALTED"
                : checkpoint.everSucceeded() ? "HEALTHY" : "PENDING");
        map.put("backfillComplete", checkpoint.backfillComplete());
        map.put("consecutiveFailures", checkpoint.consecutiveFailures());
        map.put("halted", checkpoint.halted());
        // A coarse code only; never the raw upstream message.
        map.put("lastErrorCode", checkpoint.lastErrorCode());
        map.put("nextAttemptAt", checkpoint.nextAttemptAt() == null
                ? null : checkpoint.nextAttemptAt().toString());
        return map;
    }

    private static Map<String, Object> pagination(String cursor, boolean hasMore, int pageSize) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("cursor", cursor);
        map.put("hasMore", hasMore);
        map.put("pageSize", pageSize);
        return map;
    }

    private static Map<String, Object> range(Instant from, Instant to) {
        return Map.of("from", from.toString(), "to", to.toString());
    }

    private static Instant latest(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant earliest(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
