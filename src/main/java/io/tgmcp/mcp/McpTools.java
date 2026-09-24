package io.tgmcp.mcp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import io.modelcontextprotocol.spec.McpSchema;
import io.tgmcp.access.AccessErrorCode;
import io.tgmcp.access.AuthorizedChat;
import io.tgmcp.access.ChatAccessException;
import io.tgmcp.access.ChatAccessService;
import io.tgmcp.audit.AuditAction;
import io.tgmcp.audit.AuditService;
import io.tgmcp.chat.Chat;
import io.tgmcp.config.AppProperties;
import io.tgmcp.consent.ConsentService;
import io.tgmcp.message.ChatCoverage;
import io.tgmcp.message.MessagePageKey;
import io.tgmcp.message.MessageRepository;
import io.tgmcp.message.MessageSlice;
import io.tgmcp.message.StoredMessage;
import io.tgmcp.ratelimit.RateLimiter;
import io.tgmcp.sync.SyncCheckpoint;
import io.tgmcp.telegram.gateway.ConnectionRef;
import io.tgmcp.telegram.gateway.DownloadedMedia;
import io.tgmcp.telegram.gateway.TelegramGateway;
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
    private final ConsentService consentService;
    private final CursorCodec cursorCodec;
    private final RateLimiter rateLimiter;
    private final AuditService auditService;
    private final ToolResponses responses;
    private final AppProperties.Mcp limits;
    private final Clock clock;
    private final TelegramGateway telegramGateway;

    public McpTools(ChatAccessService accessService,
                    MessageRepository messageRepository,
                    ConsentService consentService,
                    CursorCodec cursorCodec,
                    RateLimiter rateLimiter,
                    AuditService auditService,
                    ToolResponses responses,
                    AppProperties properties,
                    Clock clock,
                    TelegramGateway telegramGateway) {
        this.accessService = accessService;
        this.messageRepository = messageRepository;
        this.consentService = consentService;
        this.cursorCodec = cursorCodec;
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
        this.responses = responses;
        this.limits = properties.mcp();
        this.clock = clock;
        this.telegramGateway = telegramGateway;
    }

    // --- tools -------------------------------------------------------------------------

    public McpSchema.CallToolResult listAllowedChats(McpCallContext context, McpSchema.CallToolRequest request) {
        return guarded(context, McpToolNames.LIST_ALLOWED_CHATS, request, reportChatIds -> {
            new ToolArguments(request.arguments()).rejectUnknown(List.of());
            Instant now = this.clock.instant();

            List<Map<String, Object>> items = new ArrayList<>();
            for (AuthorizedChat allowed : this.accessService.listAllowed(context.actor(), context.provider())) {
                items.add(chatSummary(allowed, now));
            }
            Map<String, Object> payload = envelope();
            payload.put("items", items);
            payload.put("provider", context.provider());
            // Security-audit follow-up: this used to claim "whose participants all have current
            // consent for this provider", which stopped being true the moment the operator
            // removed per-participant consent gating (commit c508f30, 2026-09-20) --
            // ConsentService.coverageFor() has reported full coverage unconditionally ever
            // since, so that sentence was reaching every MCP client (and, through it, the AI
            // provider) as a false attestation on every single call. Only claim what is
            // actually still true: the operator explicitly activated the chat.
            payload.put("notice", "Only chats an operator has explicitly activated for this provider are "
                    + "listed. Other chats are omitted entirely. This server does not itself verify that "
                    + "chat participants have consented; that is the operator's own responsibility.");
            return this.responses.success(payload);
        });
    }

    public McpSchema.CallToolResult searchMessages(McpCallContext context, McpSchema.CallToolRequest request) {
        // Parsing happens inside guarded() so that an argument error becomes a structured
        // INVALID_ARGUMENT result rather than escaping as a protocol-level exception.
        return guarded(context, McpToolNames.SEARCH_MESSAGES, request, reportChatIds -> {
            ToolArguments args = new ToolArguments(request.arguments());
            args.rejectUnknown(List.of("chat_ids", "query", "from", "to", "cursor", "limit"));

            List<UUID> chatIds = args.requiredUuidList("chat_ids", this.limits.maxChatIdsPerQuery());
            reportChatIds.accept(chatIds);
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

            // Security-audit finding F14: the caller's requested range is clamped to what
            // *each* chat individually authorises, not intersected across every requested
            // chat into one shared range -- a chat approved only for January must not zero
            // out a February-approved chat's own, unrelated results.
            Map<UUID, MessageRepository.ChatWindow> windowsByChat = new LinkedHashMap<>();
            for (Map.Entry<UUID, AuthorizedChat> entry : authorized.entrySet()) {
                AuthorizedChat allowed = entry.getValue();
                Instant chatFrom = latest(from, allowed.windowStart(now));
                Instant chatTo = earliest(to, allowed.windowEnd(now));
                if (chatTo.isAfter(chatFrom)) {
                    windowsByChat.put(entry.getKey(), new MessageRepository.ChatWindow(chatFrom, chatTo));
                }
            }
            if (windowsByChat.isEmpty()) {
                Map<String, Object> empty = envelope();
                empty.put("items", List.of());
                empty.put("pagination", pagination(null, false, limit));
                empty.put("coverageByChat", coverageByChat(authorized, now));
                empty.put("notice", "The requested range lies outside every approved window.");
                return this.responses.success(empty);
            }

            MessageSlice slice = this.messageRepository.search(context.actor().tenantId(), windowsByChat, query,
                    after, limit);

            // Authorization is re-checked after the read and before anything is emitted, and
            // bound to the exact authorization snapshot ("authorized") taken above (SEC-05).
            this.accessService.reverify(context.actor(), authorized.values(), context.provider());

            // Security-audit finding SEC-03: a chat's *coverage* being complete only ever
            // describes its currently active participants -- it says nothing about whether one
            // particular stored row's own sender is eligible to be surfaced (a departed sender,
            // one never captured in chat_participants, or one whose consent has since expired).
            // Re-checked here, per message, as a backstop beyond the write-time gate in
            // ChatSyncService#store. Filtering rebuilds the slice (rather than dropping items
            // after the fact) so that capToByteBudget's cursor math -- which indexes into
            // slice.messages() -- stays aligned with the eligible messages actually rendered;
            // hasMore/nextKey are carried over unchanged since they describe the underlying
            // query, not how many of this page's rows survived the eligibility check.
            slice = new MessageSlice(slice.messages().stream()
                    .filter(message -> {
                        AuthorizedChat owner = authorized.get(message.chatId());
                        return owner != null && this.consentService.authorEligible(context.actor().tenantId(),
                                message.chatId(), message.senderTgUserId(), owner.activation().contextRevision());
                    })
                    .toList(), slice.hasMore(), slice.nextKey());

            List<Map<String, Object>> items = new ArrayList<>();
            for (StoredMessage message : slice.messages()) {
                AuthorizedChat owner = authorized.get(message.chatId());
                items.add(messageItem(message, owner == null ? null : owner.chat()));
            }

            Map<String, Object> payload = envelope();
            payload.put("query", Map.of("text", query, "matching", "PostgreSQL websearch_to_tsquery, english"));
            payload.put("requestedRange", range(from, to));
            payload.put("effectiveRangeByChat", effectiveRangeByChat(windowsByChat));
            payload.put("coverageByChat", coverageByChat(authorized, now));

            CappedPage capped = capToByteBudget(payload, items, slice, McpToolNames.SEARCH_MESSAGES,
                    context.actor(), scopeHash, queryHash, from, to);
            if (capped.items().isEmpty() && !items.isEmpty()) {
                return this.responses.error(AccessErrorCode.INVALID_ARGUMENT,
                        "A single result exceeds the response size limit; request a smaller 'limit'.");
            }
            payload.put("items", capped.items());
            payload.put("pagination", pagination(capped.cursor(), capped.hasMore(), limit));
            payload.put("truncation", ToolResponses.truncation(capped.droppedByByteCap() > 0,
                    capped.droppedByByteCap() > 0 ? "RESPONSE_BYTE_CAP" : null, capped.droppedByByteCap()));
            return this.responses.success(payload);
        });
    }

    public McpSchema.CallToolResult getChatHistory(McpCallContext context, McpSchema.CallToolRequest request) {
        return guarded(context, McpToolNames.GET_CHAT_HISTORY, request, reportChatIds -> {
            ToolArguments args = new ToolArguments(request.arguments());
            args.rejectUnknown(List.of("chat_id", "from", "to", "cursor", "limit"));

            UUID chatId = args.requiredUuid("chat_id");
            reportChatIds.accept(List.of(chatId));
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
            // Bound to the exact authorization snapshot ("allowed") taken above (SEC-05).
            this.accessService.reverify(context.actor(), List.of(allowed), context.provider());

            // Security-audit finding SEC-03: see the identical comment in searchMessages --
            // rebuilding the slice keeps capToByteBudget's index-based cursor math aligned with
            // the eligible messages actually rendered.
            String contextRevision = allowed.activation().contextRevision();
            slice = new MessageSlice(slice.messages().stream()
                    .filter(message -> this.consentService.authorEligible(context.actor().tenantId(), chatId,
                            message.senderTgUserId(), contextRevision))
                    .toList(), slice.hasMore(), slice.nextKey());

            List<Map<String, Object>> items = slice.messages().stream()
                    .map(message -> messageItem(message, allowed.chat()))
                    .toList();

            Map<String, Object> payload = envelope();
            payload.put("requestedRange", range(from, to));
            payload.put("effectiveRange", range(windowStart, windowEnd));
            payload.put("coverage", coverage(allowed, now));

            CappedPage capped = capToByteBudget(payload, items, slice, McpToolNames.GET_CHAT_HISTORY,
                    context.actor(), scopeHash, queryHash, from, to);
            if (capped.items().isEmpty() && !items.isEmpty()) {
                return this.responses.error(AccessErrorCode.INVALID_ARGUMENT,
                        "A single result exceeds the response size limit; request a smaller 'limit'.");
            }
            payload.put("items", capped.items());
            payload.put("pagination", pagination(capped.cursor(), capped.hasMore(), limit));
            payload.put("truncation", ToolResponses.truncation(capped.droppedByByteCap() > 0,
                    capped.droppedByByteCap() > 0 ? "RESPONSE_BYTE_CAP" : null, capped.droppedByByteCap()));
            return this.responses.success(payload);
        });
    }

    public McpSchema.CallToolResult getMessageContext(McpCallContext context, McpSchema.CallToolRequest request) {
        return guarded(context, McpToolNames.GET_MESSAGE_CONTEXT, request, reportChatIds -> {
            ToolArguments args = new ToolArguments(request.arguments());
            args.rejectUnknown(List.of("chat_id", "message_id", "before", "after"));

            UUID chatId = args.requiredUuid("chat_id");
            reportChatIds.accept(List.of(chatId));
            long messageId = args.requiredLong("message_id");
            int before = args.optionalInt("before", 5, 0, this.limits.maxContextMessages());
            int after = args.optionalInt("after", 5, 0, this.limits.maxContextMessages());

            Instant now = this.clock.instant();
            AuthorizedChat allowed = this.accessService.authorize(context.actor(), chatId,
                    context.provider(), true);
            Instant windowStart = allowed.windowStart(now);
            Instant windowEnd = allowed.windowEnd(now);

            String contextRevision = allowed.activation().contextRevision();
            StoredMessage pivot = this.messageRepository.find(context.actor().tenantId(), chatId, messageId)
                    // A message that is absent, deleted, or outside the approved window is
                    // reported the same way, so the caller learns nothing from the difference.
                    // Security-audit finding SEC-03: a message whose sender is no longer
                    // eligible (departed without consent, never known, or lapsed) is denied
                    // identically -- its presence or absence is not distinguishable either.
                    .filter(m -> !m.sentAt().isBefore(windowStart) && !m.sentAt().isAfter(windowEnd))
                    .filter(m -> this.consentService.authorEligible(context.actor().tenantId(), chatId,
                            m.senderTgUserId(), contextRevision))
                    .orElseThrow(ChatAccessException::denied);

            List<StoredMessage> preceding = this.messageRepository.before(context.actor().tenantId(), chatId,
                    pivot.sentAt(), pivot.tgMessageId(), windowStart, before).stream()
                    .filter(m -> this.consentService.authorEligible(context.actor().tenantId(), chatId,
                            m.senderTgUserId(), contextRevision))
                    .toList();
            List<StoredMessage> following = this.messageRepository.after(context.actor().tenantId(), chatId,
                    pivot.sentAt(), pivot.tgMessageId(), windowEnd, after).stream()
                    .filter(m -> this.consentService.authorEligible(context.actor().tenantId(), chatId,
                            m.senderTgUserId(), contextRevision))
                    .toList();

            // Bound to the exact authorization snapshot ("allowed") taken above (SEC-05).
            this.accessService.reverify(context.actor(), List.of(allowed), context.provider());

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

    /**
     * Fetches a message's visual preview on demand -- never pre-downloaded, never cached
     * anywhere in this application, so a revoked chat or a withdrawn consent takes effect on
     * the very next call with nothing left over to separately purge. Follows the identical
     * authorize -> read -> re-verify shape as {@link #getMessageContext} for the message lookup
     * itself; the only new step is the actual byte fetch through {@link TelegramGateway}.
     */
    public McpSchema.CallToolResult getMessageMedia(McpCallContext context, McpSchema.CallToolRequest request) {
        return guarded(context, McpToolNames.GET_MESSAGE_MEDIA, request, reportChatIds -> {
            ToolArguments args = new ToolArguments(request.arguments());
            args.rejectUnknown(List.of("chat_id", "message_id"));

            UUID chatId = args.requiredUuid("chat_id");
            reportChatIds.accept(List.of(chatId));
            long messageId = args.requiredLong("message_id");

            Instant now = this.clock.instant();
            AuthorizedChat allowed = this.accessService.authorize(context.actor(), chatId,
                    context.provider(), true);
            Instant windowStart = allowed.windowStart(now);
            Instant windowEnd = allowed.windowEnd(now);
            String contextRevision = allowed.activation().contextRevision();

            StoredMessage message = this.messageRepository.find(context.actor().tenantId(), chatId, messageId)
                    // Absent, deleted, outside the window, or an ineligible sender are all
                    // denied identically -- see the matching comment in getMessageContext.
                    .filter(m -> !m.sentAt().isBefore(windowStart) && !m.sentAt().isAfter(windowEnd))
                    .filter(m -> this.consentService.authorEligible(context.actor().tenantId(), chatId,
                            m.senderTgUserId(), contextRevision))
                    .orElseThrow(ChatAccessException::denied);

            // Bound to the exact authorization snapshot ("allowed") taken above (SEC-05).
            this.accessService.reverify(context.actor(), List.of(allowed), context.provider());

            if (message.mediaKind() == null) {
                return this.responses.success(unavailable(chatId, messageId,
                        "This message has no visual preview to fetch."));
            }

            ConnectionRef ref = new ConnectionRef(allowed.chat().tenantId(), allowed.chat().connectionId());
            Optional<DownloadedMedia> media = this.telegramGateway.downloadMessageMedia(
                    ref, message.mediaRemoteFileId());
            if (media.isEmpty()) {
                return this.responses.success(unavailable(chatId, messageId,
                        "This message's media reference no longer resolves -- deleted upstream, or too old."));
            }
            // Security-audit finding L1: what actually goes over the wire is the base64-encoded
            // string below, not the raw bytes -- about a third larger. Comparing the raw byte
            // count against the cap let a response through whose real size exceeded it by up to
            // that margin. Encoding once here and reusing the same string for both the check and
            // the response also avoids encoding the same bytes twice.
            String base64 = Base64.getEncoder().encodeToString(media.get().bytes());
            if (base64.length() > this.limits.maxResponseBytes()) {
                return this.responses.success(unavailable(chatId, messageId,
                        "This preview exceeds the response size limit and cannot be returned."));
            }

            Map<String, Object> payload = envelope();
            payload.put("chatId", chatId.toString());
            payload.put("messageId", String.valueOf(messageId));
            payload.put("available", true);
            payload.put("previewKind", message.mediaKind());
            payload.put("notice", "PHOTO".equals(message.mediaKind())
                    ? "This is the message's own photo."
                    : "This is a single representative thumbnail frame Telegram already generated for this "
                            + "video/animation, not the full video -- full video content is never fetched.");

            McpSchema.CallToolResult structured = this.responses.success(payload);
            List<McpSchema.Content> content = new ArrayList<>(structured.content());
            content.add(new McpSchema.ImageContent(null, base64, media.get().mimeType()));
            return McpSchema.CallToolResult.builder()
                    .content(content)
                    .structuredContent(structured.structuredContent())
                    .isError(false)
                    .build();
        });
    }

    private Map<String, Object> unavailable(UUID chatId, long messageId, String notice) {
        Map<String, Object> payload = envelope();
        payload.put("chatId", chatId.toString());
        payload.put("messageId", String.valueOf(messageId));
        payload.put("available", false);
        payload.put("notice", notice);
        return payload;
    }

    public McpSchema.CallToolResult getSyncStatus(McpCallContext context, McpSchema.CallToolRequest request) {
        return guarded(context, McpToolNames.GET_SYNC_STATUS, request, reportChatIds -> {
            ToolArguments args = new ToolArguments(request.arguments());
            args.rejectUnknown(List.of("chat_id"));
            UUID chatId = args.requiredUuid("chat_id");
            reportChatIds.accept(List.of(chatId));

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

    /**
     * Security-audit finding F20: {@code guarded} used to take a single {@code chatId}, fixed
     * at the call site before any request argument had been parsed -- every call site passed
     * {@code null}, so every audit row this method wrote (rate-limited, allowed, denied) was
     * unattributed to any chat, for every tool, not only the multi-chat ones. {@code body} is
     * instead handed a callback it invokes with the chat id(s) the request actually names, as
     * soon as they are parsed and before anything that could throw runs -- so a denial is
     * attributed exactly as precisely as a success. A tool touching several chats (only {@link
     * #searchMessages}) reports all of them and gets one audit row per chat, matching how every
     * other multi-chat action in this codebase (see {@code ChatActivationService#pause} from
     * finding F16) already records one row per chat rather than one row with none.
     *
     * <p>Security-audit finding SEC-15 fixes three further gaps in this same method: (1) a
     * rate-limit rejection happens before {@code body} ever runs, so {@code reportChatIds} had
     * never been called yet -- {@code request} is used for a lenient, unvalidated, best-effort
     * extraction of whatever chat id(s) it names, purely so that denial is still attributable;
     * (2) {@code body} can return a structured error result (see {@link ToolResponses#error})
     * without throwing at all -- previously unconditionally logged as {@code ALLOWED} because
     * nothing inspected {@link McpSchema.CallToolResult#isError()}; (3) a generic {@link
     * RuntimeException} was logged but never recorded as a structured audit event at all.
     */
    private McpSchema.CallToolResult guarded(McpCallContext context, String tool,
                                             McpSchema.CallToolRequest request,
                                             Function<Consumer<List<UUID>>, McpSchema.CallToolResult> body) {
        List<UUID> touchedChatIds = new ArrayList<>();
        Consumer<List<UUID>> reportChatIds = ids -> {
            touchedChatIds.clear();
            touchedChatIds.addAll(ids);
        };
        String rateKey = context.actor().tenantId() + ":" + context.actor().userId();
        if (!this.rateLimiter.tryAcquire(rateKey)) {
            recordPerChat(context, AuditAction.MCP_RATE_LIMITED, bestEffortChatIds(request), AuditService.DENIED,
                    Map.of("tool", tool));
            return this.responses.error(AccessErrorCode.RATE_LIMITED,
                    "Too many requests. Retry shortly.");
        }
        try {
            McpSchema.CallToolResult result = body.apply(reportChatIds);
            if (Boolean.TRUE.equals(result.isError())) {
                recordPerChat(context, AuditAction.MCP_TOOL_CALL, touchedChatIds, AuditService.ERROR,
                        Map.of("tool", tool, "provider", context.provider()));
            }
            else {
                recordPerChat(context, AuditAction.MCP_TOOL_CALL, touchedChatIds, AuditService.ALLOWED,
                        Map.of("tool", tool, "provider", context.provider()));
            }
            return result;
        }
        catch (ChatAccessException ex) {
            recordPerChat(context, AuditAction.MCP_ACCESS_DENIED, touchedChatIds, AuditService.DENIED,
                    Map.of("tool", tool, "code", ex.code().name()));
            return this.responses.error(ex.code(), ex.getMessage());
        }
        catch (CursorException ex) {
            recordPerChat(context, AuditAction.MCP_ACCESS_DENIED, touchedChatIds, AuditService.DENIED,
                    Map.of("tool", tool, "code", "INVALID_CURSOR"));
            return this.responses.error(AccessErrorCode.INVALID_ARGUMENT,
                    "The pagination cursor is not valid for this request. Start a new query.");
        }
        catch (RuntimeException ex) {
            // Never surface internal detail to an MCP client.
            log.error("MCP tool {} failed", tool, ex);
            recordPerChat(context, AuditAction.MCP_TOOL_CALL, touchedChatIds, AuditService.ERROR,
                    Map.of("tool", tool, "error", ex.getClass().getSimpleName()));
            return this.responses.error(AccessErrorCode.INTERNAL_ERROR,
                    "The request could not be completed.");
        }
    }

    /**
     * Security-audit finding SEC-15: a rate-limited call is rejected before {@code guarded}'s
     * {@code body} runs any tool-specific argument parsing, so the resulting audit row would
     * otherwise carry no chat attribution at all. Reads whatever the raw, not-yet-validated
     * request names under the conventional {@code chat_id}/{@code chat_ids} argument names --
     * best effort, silently ignoring anything that fails to parse as a UUID -- purely so that
     * denial can still be attributed to the chat(s) it was actually about. Never used for
     * authorization; only {@code guarded}'s real, validated parsing inside {@code body} is.
     */
    private static List<UUID> bestEffortChatIds(McpSchema.CallToolRequest request) {
        List<UUID> ids = new ArrayList<>();
        Object single = request.arguments().get("chat_id");
        if (single instanceof String text) {
            parseUuid(text).ifPresent(ids::add);
        }
        Object many = request.arguments().get("chat_ids");
        if (many instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String text) {
                    parseUuid(text).ifPresent(ids::add);
                }
            }
        }
        return ids;
    }

    private static java.util.Optional<UUID> parseUuid(String text) {
        try {
            return java.util.Optional.of(UUID.fromString(text));
        }
        catch (IllegalArgumentException ex) {
            return java.util.Optional.empty();
        }
    }

    private void recordPerChat(McpCallContext context, String action, List<UUID> chatIds, String outcome,
                               Map<String, Object> detail) {
        if (chatIds.isEmpty()) {
            this.auditService.record(context.actor(), action, null, outcome, detail);
            return;
        }
        for (UUID chatId : chatIds) {
            this.auditService.record(context.actor(), action, chatId, outcome, detail);
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
        // A photo, video, video note or animation: message.mediaKind() names which, and
        // get_message_media fetches the bytes (the photo itself, or a video's representative
        // thumbnail frame) fresh -- never pre-downloaded or included here.
        item.put("media", message.mediaKind() == null ? null : Map.of(
                "kind", message.mediaKind(),
                "fetchWith", McpToolNames.GET_MESSAGE_MEDIA));
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
        // Renamed from "participantsRequiringConsent": since the operator's explicit decision
        // (commit c508f30) to remove per-participant consent gating, ConsentService.coverageFor
        // always reports full coverage -- this number was never actually "how many still need
        // to consent" after that change, only ever the chat's total non-bot participant count,
        // which misled at least one caller into asking who that outstanding participant was
        // when nobody was actually outstanding. Kept as a plain informational count, not
        // reframed as a gating signal it has not been since c508f30.
        item.put("participantCount", allowed.coverage().requiredParticipants());
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

    /**
     * Security-audit finding F14: replaces a single shared {@code effectiveRange} for a
     * multi-chat search, which could only ever describe one (wrong, intersected) range. A
     * chat absent here had no overlap between the caller's requested range and its own
     * approved window, and therefore contributed no results.
     */
    private Map<String, Object> effectiveRangeByChat(Map<UUID, MessageRepository.ChatWindow> windowsByChat) {
        Map<String, Object> map = new LinkedHashMap<>();
        windowsByChat.forEach((chatId, window) -> map.put(chatId.toString(), range(window.from(), window.to())));
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

    /**
     * A page of items whose cursor is guaranteed to resume exactly where the returned items
     * stop -- even when the byte cap cut the page short. {@code items} is empty and {@code
     * droppedByByteCap} equals the original count when not even one item fit.
     */
    private record CappedPage(List<Map<String, Object>> items, String cursor, boolean hasMore,
                              int droppedByByteCap) {
    }

    /**
     * Security-audit finding F15: recomputes the pagination cursor from however many items
     * actually survive the byte cap, rather than from the original, untrimmed database page.
     * {@link ToolResponses#success} trims independently but has no way to rebuild a
     * tool-specific, signed cursor -- leaving the previous code's cursor pointing past
     * whatever it had already computed for the full page, so a page cut short by the byte cap
     * made its own dropped tail permanently unreachable on the next request.
     */
    private CappedPage capToByteBudget(Map<String, Object> payloadSoFar, List<Map<String, Object>> items,
                                       MessageSlice slice, String tool, AuthenticatedActor actor,
                                       String scopeHash, String queryHash, Instant from, Instant to) {
        if (items.isEmpty()) {
            String cursor = slice.nextKey() == null ? null
                    : this.cursorCodec.encode(tool, actor, scopeHash, queryHash, from, to, slice.nextKey());
            return new CappedPage(items, cursor, slice.hasMore(), 0);
        }

        // A representative cursor of the same shape as whatever the real one turns out to be,
        // purely so the byte-fitting probe below sizes the "pagination" field realistically.
        MessagePageKey sizingKey = slice.nextKey() != null ? slice.nextKey() : keyOf(slice.messages().get(0));
        String sizingCursor = this.cursorCodec.encode(tool, actor, scopeHash, queryHash, from, to, sizingKey);
        Map<String, Object> probe = new LinkedHashMap<>(payloadSoFar);
        probe.put("items", items);
        probe.put("pagination", pagination(sizingCursor, true, items.size()));
        int fitting = this.responses.fittingCount(probe, items);

        if (fitting == items.size()) {
            String cursor = slice.nextKey() == null ? null
                    : this.cursorCodec.encode(tool, actor, scopeHash, queryHash, from, to, slice.nextKey());
            return new CappedPage(items, cursor, slice.hasMore(), 0);
        }
        if (fitting == 0) {
            // Not even one item fits; advancing no cursor here is deliberate -- see caller.
            return new CappedPage(List.of(), null, true, items.size());
        }
        List<Map<String, Object>> retained = items.subList(0, fitting);
        MessagePageKey cursorKey = keyOf(slice.messages().get(fitting - 1));
        String cursor = this.cursorCodec.encode(tool, actor, scopeHash, queryHash, from, to, cursorKey);
        return new CappedPage(retained, cursor, true, items.size() - fitting);
    }

    private static MessagePageKey keyOf(StoredMessage message) {
        return new MessagePageKey(message.sentAt(), message.chatId(), message.tgMessageId());
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
