package io.tgmcp.telegram.tdlib;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import io.tgmcp.common.KeyMaterial;
import io.tgmcp.config.AppProperties;
import io.tgmcp.telegram.gateway.ConnectionRef;
import io.tgmcp.telegram.gateway.LoginMethod;
import io.tgmcp.telegram.gateway.LoginStage;
import io.tgmcp.telegram.gateway.LoginState;
import io.tgmcp.telegram.gateway.MessageWindow;
import io.tgmcp.telegram.gateway.RemoteChat;
import io.tgmcp.telegram.gateway.RemoteMembership;
import io.tgmcp.telegram.gateway.RemoteMessage;
import io.tgmcp.telegram.gateway.RemoteMessagePage;
import io.tgmcp.telegram.gateway.RemoteParticipant;
import io.tgmcp.telegram.gateway.TelegramAdapterKind;
import io.tgmcp.telegram.gateway.TelegramAuthException;
import io.tgmcp.telegram.gateway.TelegramGateway;
import io.tgmcp.telegram.gateway.TelegramPermissionException;
import io.tgmcp.telegram.gateway.TelegramTransientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Real Telegram adapter, speaking TDLib's documented JSON API through {@link TdlibClient}.
 *
 * <p>Only read methods are used: {@code getChats}, {@code getChat}, {@code getChatHistory},
 * {@code getSupergroupMembers}, {@code getMessageLink}. Nothing here can send, edit, delete
 * or join, because the {@link TelegramGateway} port offers nowhere to put such a call.
 *
 * <p><strong>Verification status:</strong> the JSON translation in this class is covered by
 * unit tests against a fake {@link TdlibClient}. It has not been exercised against a live
 * Telegram account — see {@code docs/production-prerequisites.md}.
 */
public class TdlibTelegramGateway implements TelegramGateway {

    private static final Logger log = LoggerFactory.getLogger(TdlibTelegramGateway.class);

    private static final int MAX_HISTORY_BATCH = 100;
    /**
     * Safety cap on how many raw {@code getChatHistory} pages one fetchMessages() call will
     * walk through looking for window-eligible content. Security-audit finding F12: without an
     * internal loop at all, a single call only ever looked at the single newest raw page, so a
     * chat with more history "in the way" (newer-than-window noise ahead of catch-up's target,
     * or an all-outside-window page during backfill) could never reach it. This cap exists only
     * to bound the work one call may do; hitting it returns hasMore=true with a resume point
     * (see RemoteMessagePage#resumeCursor) rather than silently giving up.
     */
    static final int MAX_HISTORY_RAW_PAGES_PER_CALL = 20;
    private static final int MEMBER_PAGE_SIZE = 200;
    /**
     * Safety cap on how many members one listParticipants() call will ever page through.
     * Security-audit finding F04: this used to fetch a single fixed 200-member page, so any
     * supergroup larger than that silently reported only its first 200 members -- understating
     * who is actually in the chat and who therefore needs to consent.
     */
    static final int MAX_MEMBERS = 5000;

    private final TdlibClient client;
    private final AppProperties.Telegram config;
    private final AtomicReference<LoginState> loginState =
            new AtomicReference<>(LoginState.of(LoginStage.DISCONNECTED, null));
    /**
     * Security-audit finding F13: the single owner this listener belongs to, mirroring the
     * single-shared-native-client assumption {@link io.tgmcp.telegram.TelegramConnectionService}
     * already enforces (F01) -- there is at most one active TDLIB login at a time, so one slot
     * is enough. Notified from {@link #handleUpdate} for every authorization-state change,
     * including ones that happen asynchronously (a QR code scanned on another device) well
     * after whatever request/response call originally triggered the login.
     */
    private volatile Consumer<LoginState> stateListener;
    /**
     * Security-audit finding SEC-01: every gateway method accepted a {@link ConnectionRef} but
     * none of them checked it against anything -- {@link io.tgmcp.telegram.TelegramConnectionService}
     * enforces single ownership only at {@code beginLogin} time, and every other operation (a
     * status poll, code/password submission, discovery, disconnect) reached this class with
     * whatever ref the caller's own database row happened to carry, regardless of who currently
     * owns the one shared native session. A second application user who had merely visited
     * {@code /connect} (creating their own, still-disconnected row) could poll the real owner's
     * live status and QR link, or disconnect their live session outright, and this class would
     * have honoured it. This field is the one ref allowed to drive the shared client at all;
     * {@link #beginLogin} and {@link #onStateChange} are the only methods allowed to (re)claim
     * it, since both are only reachable through {@code TelegramConnectionService.beginLogin}
     * after it has atomically verified this actor may become the new owner. Every other method
     * calls {@link #requireCurrentOwner} first and touches no native state at all if the ref
     * does not match -- fail closed, not fail open.
     */
    private volatile ConnectionRef currentOwner;
    /**
     * Base64-encoded (TDLib's JSON interface represents {@code bytes} fields this way) key for
     * TDLib's own at-rest encryption of {@link AppProperties.Telegram#databaseDirectory()}.
     * Security-audit finding F19: {@code setTdlibParameters} used to omit this field entirely,
     * leaving the local session/chat-metadata database TDLib maintains on disk unencrypted.
     * Derived from the same {@code tgmcp.secrets.session-encryption-key} this application
     * already requires before authorizing real account access (see {@link
     * io.tgmcp.config.ProductionSafetyValidator}), rather than asking for a fourth secret to
     * configure -- {@link KeyMaterial#derive} keeps it cryptographically distinct from the AES
     * key {@link io.tgmcp.telegram.session.SessionCipher} derives from the same root secret.
     */
    private final String databaseEncryptionKey;
    /**
     * Security-audit finding F08: TDLib delivers edits and deletions as pushed {@code
     * updateMessageContent} / {@code updateDeleteMessages} events, not as a query this class
     * could answer "what changed since X" with the way {@code MockTelegramGateway}'s real
     * {@code fetchRevisions} implementation does. This buffers each pushed revision, keyed by
     * the chat it belongs to, until the next {@link #fetchRevisions} call drains it -- bridging
     * TDLib's push model onto {@code ChatSyncService}'s pull-based contract without changing
     * that contract, {@link TelegramGateway}, or {@link RemoteMessage} at all. In-memory only:
     * a revision buffered here does not survive a process restart before the next sync drains
     * it. That is an accepted, narrower gap than "no pipeline exists at all" -- see
     * docs/production-prerequisites.md.
     */
    private final Map<Long, Deque<RemoteMessage>> pendingRevisions = new ConcurrentHashMap<>();
    /** Caps memory if a chat accumulates edits/deletions faster than it is ever synced. */
    private static final int MAX_PENDING_REVISIONS_PER_CHAT = 500;

    public TdlibTelegramGateway(TdlibClient client, AppProperties.Telegram config, String sessionEncryptionKey) {
        this.client = client;
        this.config = config;
        this.databaseEncryptionKey = deriveDatabaseEncryptionKey(sessionEncryptionKey);
        // Security-audit finding F11: registered before start() so this listener cannot miss
        // the very first update TDLib sends (in practice, the initial updateAuthorizationState).
        this.client.onUpdate(this::handleUpdate);
        this.client.start();
    }

    static String deriveDatabaseEncryptionKey(String sessionEncryptionKey) {
        byte[] derived = KeyMaterial.derive(sessionEncryptionKey, "session-encryption", "tdlib-database-encryption-key");
        return Base64.getEncoder().encodeToString(derived);
    }

    @Override
    public TelegramAdapterKind kind() {
        return TelegramAdapterKind.TDLIB;
    }

    @Override
    public void onStateChange(ConnectionRef ref, Consumer<LoginState> listener) {
        // Claims ownership rather than checking it: TelegramConnectionService.beginLogin calls
        // this before the native beginLogin request (security-audit finding F13, so an async
        // transition cannot be missed), which means this is the first gateway call a brand new
        // legitimate owner makes. It is only reachable after that service method's own atomic
        // single-owner check has already passed.
        this.currentOwner = ref;
        this.stateListener = listener;
    }

    /**
     * Security-audit finding SEC-01: the one gate every non-claiming method shares. Throws
     * rather than silently operating on the wrong owner's behalf, and does so before any native
     * request is made -- a rejected ref must never reach TDLib at all.
     */
    private void requireCurrentOwner(ConnectionRef ref) {
        if (!java.util.Objects.equals(ref, this.currentOwner)) {
            throw new TelegramAuthException(
                    "This connection is not the current owner of the shared Telegram session");
        }
    }

    // --- authorization ----------------------------------------------------------------

    private void handleUpdate(JsonNode update) {
        switch (update.path("@type").asString("")) {
            case "updateMessageContent" -> handleMessageContentUpdate(update);
            case "updateDeleteMessages" -> handleDeleteMessagesUpdate(update);
            case "updateAuthorizationState" -> handleAuthorizationStateUpdate(update);
            default -> { }
        }
    }

    /**
     * Security-audit finding F08: the edit half of the revision pipeline. {@code
     * updateMessageContent} carries only the new content inline; re-fetching the full message
     * via {@code getMessage} keeps the mapping identical to every other {@link RemoteMessage}
     * this class produces ({@link #toRemoteMessage}) instead of hand-assembling a partial one
     * from the push event's own fields.
     */
    private void handleMessageContentUpdate(JsonNode update) {
        long chatId = update.path("chat_id").asLong();
        long messageId = update.path("message_id").asLong();
        try {
            JsonNode message = this.client.request("getMessage", Map.of("chat_id", chatId, "message_id", messageId));
            bufferRevision(chatId, toRemoteMessage(message));
        }
        catch (RuntimeException ex) {
            // Best-effort: the edited message may already be gone by the time this follow-up
            // request runs (e.g. it was deleted moments later) -- the corresponding
            // updateDeleteMessages event, which carries no such race, still captures that case.
            log.warn("Could not fetch edited message {} in chat {} after updateMessageContent: {}",
                    messageId, chatId, ex.getClass().getSimpleName());
        }
    }

    /**
     * Security-audit finding F08: the delete half of the revision pipeline. A deleted message
     * carries no retrievable content, and {@code ChatSyncService.applyRevisions}/{@code store}
     * route a {@code deleted()} revision straight to {@code MessageRepository.markDeleted}
     * without reading any other field of it, so the placeholder fields here are never read.
     */
    private void handleDeleteMessagesUpdate(JsonNode update) {
        long chatId = update.path("chat_id").asLong();
        for (JsonNode idNode : update.path("message_ids")) {
            bufferRevision(chatId, new RemoteMessage(idNode.asLong(), null, null, Instant.EPOCH, null, true, "",
                    null));
        }
    }

    private void bufferRevision(long tgChatId, RemoteMessage revision) {
        Deque<RemoteMessage> queue = this.pendingRevisions.computeIfAbsent(tgChatId,
                id -> new ConcurrentLinkedDeque<>());
        queue.addLast(revision);
        RemoteMessage dropped;
        while (queue.size() > MAX_PENDING_REVISIONS_PER_CHAT && (dropped = queue.pollFirst()) != null) {
            log.warn("Dropped a buffered revision for chat {} (message {}): more than {} edits/deletions "
                            + "accumulated without a sync draining them",
                    tgChatId, dropped.messageId(), MAX_PENDING_REVISIONS_PER_CHAT);
        }
    }

    private void handleAuthorizationStateUpdate(JsonNode update) {
        JsonNode state = update.path("authorization_state");
        // Real-account testing found that quieting TDLib's own very noisy native logging
        // (necessary -- see NativeTdlibClient -- since it was flooding Railway's log pipe past
        // its rate limit) also silently removed the only visibility into which stage a login
        // attempt actually reached. This one line, at the application's own log level rather
        // than TDLib's, replaces it: one line per real transition, never per-file/per-reaction
        // engine noise.
        log.info("TDLib authorization state changed to {}", state.path("@type").asString("unknown"));
        LoginState mapped = mapAuthorizationState(state);
        // Security-audit finding F13: authorizationStateReady on its own carries no account
        // identity. Fetching it here -- rather than leaving tgUserId/tgUsername null until
        // some later request happens to populate them -- means whatever this update notifies
        // listeners with (see below) is already complete. Safe to call now rather than
        // deadlocking: security-audit finding F11 moved listener dispatch off the receive
        // thread specifically so a listener may call request() itself.
        if (mapped.stage() == LoginStage.READY) {
            mapped = withAccountIdentity(mapped);
            // Real-account testing found every per-chat call (getChat, and so getChatHistory
            // and listParticipants which both start from it) fail with "Chat not found" for a
            // chat this TDLib session has not yet loaded into its runtime chat list -- true for
            // every chat right after each process restart, even one this app already knows
            // about from a previous session, because loadChats/getChats populate an in-memory
            // list separate from TDLib's own on-disk chat-info database. Until an operator
            // happened to click "Refresh chat list" (the only place this was previously called),
            // every already-activated chat's sync and participant refresh failed outright and
            // TdlibErrors.translate() maps that 400 to a permission failure, halting sync -- so
            // an MCP client connecting right after a restart saw an ACTIVE chat with nothing
            // ever synced into it. Warming the list here, once per session, as soon as it is
            // possible to call anything at all, removes the dependency on that manual click.
            // Best-effort, like withAccountIdentity just above: a failure here must not stop
            // this READY transition itself from being recorded, since the ordinary "Refresh
            // chat list" button remains a fallback path to the same warmed state.
            try {
                warmChatList();
            }
            catch (RuntimeException ex) {
                log.warn("Reached authorizationStateReady but could not warm the chat list: {}",
                        ex.getClass().getSimpleName());
            }
        }
        this.loginState.set(mapped);
        if ("authorizationStateWaitTdlibParameters".equals(state.path("@type").asString(""))) {
            // A fresh epoch (cold start, or right after a logout) that needs parameters sent
            // again, regardless of whether a previous epoch already sent them.
            this.tdlibParametersPending = true;
            ensureTdlibParametersSent();
        }
        Consumer<LoginState> listener = this.stateListener;
        if (listener != null) {
            listener.accept(mapped);
        }
    }

    private LoginState withAccountIdentity(LoginState state) {
        try {
            JsonNode me = this.client.request("getMe", Map.of());
            long tgUserId = me.path("id").asLong();
            String username = me.path("usernames").path("editable_username").asString(null);
            return new LoginState(state.stage(), state.qrLoginLink(), tgUserId, username, state.message());
        }
        catch (RuntimeException ex) {
            log.warn("Reached authorizationStateReady but could not fetch account identity via getMe: {}",
                    ex.getClass().getSimpleName());
            return state;
        }
    }

    LoginState mapAuthorizationState(JsonNode state) {
        return switch (state.path("@type").asString("")) {
            case "authorizationStateWaitPhoneNumber" ->
                    LoginState.of(LoginStage.WAIT_PHONE_NUMBER, "login.tdlib.waitPhone");
            case "authorizationStateWaitCode" ->
                    LoginState.of(LoginStage.WAIT_CODE, "login.tdlib.waitCode");
            case "authorizationStateWaitOtherDeviceConfirmation" ->
                    new LoginState(LoginStage.WAIT_QR_SCAN, state.path("link").asString(null), null, null,
                            "login.tdlib.waitQrScan");
            case "authorizationStateWaitPassword" ->
                    LoginState.of(LoginStage.WAIT_PASSWORD, "login.tdlib.waitPassword");
            case "authorizationStateReady" -> LoginState.of(LoginStage.READY, "login.tdlib.ready");
            case "authorizationStateLoggingOut", "authorizationStateClosing", "authorizationStateClosed" ->
                    LoginState.of(LoginStage.DISCONNECTED, "login.tdlib.disconnected");
            case "authorizationStateWaitTdlibParameters" ->
                    LoginState.of(LoginStage.DISCONNECTED, "login.tdlib.initialising");
            default -> LoginState.of(LoginStage.ERROR,
                    "Unsupported Telegram authorization state: " + state.path("@type").asString("unknown"));
        };
    }

    /**
     * Confirmed from live production logs: {@code authorizationStateWaitTdlibParameters} (seen
     * after every cold start, and again after every logout/re-init) triggers {@link
     * #sendTdlibParameters} asynchronously from the update-listener-dispatch thread (SEC-14).
     * {@link #beginLogin} runs on an unrelated HTTP request thread and could reach TDLib with
     * its own login-specific request before that dispatch had actually run, which TDLib
     * correctly rejected with "Initialization parameters are needed: call setTdlibParameters
     * first" -- surfacing to the operator as an unhandled error on an ordinary reconnect attempt
     * right after clicking Disconnect. Both call sites now go through this guarded,
     * at-most-once-per-epoch send instead of calling {@link #sendTdlibParameters} directly:
     * whichever of them runs first actually sends the parameters, and the other is a no-op.
     * {@code synchronized} rather than a lock-free compare-and-set because {@link
     * #sendTdlibParameters} itself blocks on a real TDLib round trip -- the second caller must
     * wait for that round trip to finish (and see {@code pending=false}), not race past it.
     */
    private volatile boolean tdlibParametersPending = true;

    private synchronized void ensureTdlibParametersSent() {
        if (this.tdlibParametersPending) {
            sendTdlibParameters();
            this.tdlibParametersPending = false;
        }
    }

    private void sendTdlibParameters() {
        requireRealAccessAuthorized();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("use_test_dc", false);
        params.put("database_directory", this.config.databaseDirectory());
        params.put("files_directory", this.config.databaseDirectory() + "/files");
        params.put("use_file_database", false);
        params.put("use_chat_info_database", true);
        params.put("use_message_database", false);
        // Secret Chats are explicitly out of scope for this service.
        params.put("use_secret_chats", false);
        // Security-audit finding F19: without this, TDLib stores the local session and
        // chat-metadata database at database_directory completely unencrypted.
        params.put("database_encryption_key", this.databaseEncryptionKey);
        params.put("api_id", Integer.parseInt(this.config.apiId()));
        params.put("api_hash", this.config.apiHash());
        params.put("system_language_code", "en");
        params.put("device_model", "telegram-consent-mcp");
        params.put("application_version", "0.1.0");
        this.client.request("setTdlibParameters", params);
    }

    @Override
    public LoginState beginLogin(ConnectionRef ref, LoginMethod method, String phoneNumber) {
        requireRealAccessAuthorized();
        // Claims ownership, same as onStateChange above and for the same reason: this is the
        // other of the two calls TelegramConnectionService.beginLogin makes to establish a
        // brand new, already-verified owner.
        this.currentOwner = ref;
        // See ensureTdlibParametersSent's own doc: closes the race where the request just below
        // could otherwise reach TDLib before the async listener path has sent
        // setTdlibParameters for the current epoch (a fresh process, or a login attempted right
        // after disconnecting).
        ensureTdlibParametersSent();
        if (method == LoginMethod.QR_CODE) {
            this.client.request("requestQrCodeAuthentication", Map.of("other_user_ids", List.of()));
        }
        else {
            this.client.request("setAuthenticationPhoneNumber",
                    Map.of("phone_number", phoneNumber, "settings", Map.of("@type",
                            "phoneNumberAuthenticationSettings")));
        }
        return this.loginState.get();
    }

    @Override
    public LoginState submitCode(ConnectionRef ref, String code) {
        requireCurrentOwner(ref);
        this.client.request("checkAuthenticationCode", Map.of("code", code));
        return this.loginState.get();
    }

    @Override
    public LoginState submitPassword(ConnectionRef ref, String password) {
        requireCurrentOwner(ref);
        this.client.request("checkAuthenticationPassword", Map.of("password", password));
        return this.loginState.get();
    }

    @Override
    public LoginState currentState(ConnectionRef ref) {
        requireCurrentOwner(ref);
        return this.loginState.get();
    }

    @Override
    public void disconnect(ConnectionRef ref) {
        requireCurrentOwner(ref);
        // logOut ends the Telegram-side session for this client; close only detaches locally.
        this.client.request("logOut", Map.of());
        this.loginState.set(LoginState.of(LoginStage.DISCONNECTED, "login.tdlib.signedOut"));
        // Security-audit finding SEC-01: releases the claim so a future legitimate owner's
        // beginLogin/onStateChange starts from a clean slate rather than this stale ref lingering
        // as "current" until someone else's login happens to overwrite it.
        this.currentOwner = null;
        // Security-audit finding F08: a revision buffered before this disconnect belongs to
        // whatever account was just signed out of; a future login (the same account signing
        // back in, or a different one) must not have it silently applied on its behalf.
        this.pendingRevisions.clear();
        // Confirmed from live production logs: logOut does not synchronously push
        // authorizationStateWaitTdlibParameters -- TDLib only announces that state once
        // something (the next request) actually prompts it to, well after this method
        // returns. ensureTdlibParametersSent's own guard was still seeing the *previous*
        // epoch's tdlibParametersPending=false at that point, so a login attempted right after
        // this disconnect skipped resending parameters and reached TDLib as
        // requestQrCodeAuthentication/setAuthenticationPhoneNumber, which TDLib correctly
        // rejected with "Initialization parameters are needed". Setting this here, at the one
        // moment this class can be certain a fresh epoch has begun, closes that gap without
        // waiting for a push that may arrive too late.
        this.tdlibParametersPending = true;
    }

    // --- read-only data ---------------------------------------------------------------

    @Override
    public List<RemoteChat> listAvailableChats(ConnectionRef ref) {
        requireCurrentOwner(ref);
        JsonNode chats = warmChatList();

        List<RemoteChat> result = new ArrayList<>();
        for (JsonNode idNode : chats.path("chat_ids")) {
            JsonNode chat = this.client.request("getChat", Map.of("chat_id", idNode.asLong()));
            result.add(toRemoteChat(chat));
        }
        return List.copyOf(result);
    }

    /**
     * Populates TDLib's runtime chat list (a separate, in-memory concept from its own on-disk
     * chat-info database -- see the caller in {@link #handleUpdate}) and returns what {@code
     * getChats} reports for it, so every chat this account can see becomes reachable by {@code
     * getChat}/{@code getChatHistory} afterward. Safe and cheap to call more than once per
     * session: once the list is fully loaded, {@code loadChats} answers 404 ("Not Found"), which
     * is documented TDLib behaviour for "nothing left to load", not a failure.
     */
    private JsonNode warmChatList() {
        try {
            this.client.request("loadChats", Map.of("chat_list", Map.of("@type", "chatListMain"), "limit", 200));
        }
        catch (TelegramTransientException ignored) {
            // TdlibErrors.translate() has no specific mapping for code 404 (it is neither a
            // flood wait, an auth failure, a permission failure, nor a malformed request), so it
            // falls through to TelegramTransientException -- not TelegramPermissionException,
            // which this catch clause caught until real-account testing hit exactly this path
            // and found the "refresh chat list" button crashing every single time with that 404
            // propagating straight out to the generic error page, uncaught.
        }
        return this.client.request("getChats", Map.of("chat_list", Map.of("@type", "chatListMain"), "limit", 200));
    }

    RemoteChat toRemoteChat(JsonNode chat) {
        JsonNode type = chat.path("type");
        String chatType = switch (type.path("@type").asString("")) {
            case "chatTypePrivate" -> "PRIVATE";
            case "chatTypeBasicGroup" -> "GROUP";
            case "chatTypeSupergroup" -> type.path("is_channel").asBoolean(false) ? "CHANNEL" : "SUPERGROUP";
            default -> "UNKNOWN";
        };
        return new RemoteChat(chat.path("id").asLong(), chat.path("title").asString(""), chatType, null, null);
    }

    @Override
    public RemoteMembership listParticipants(ConnectionRef ref, long tgChatId) {
        requireCurrentOwner(ref);
        JsonNode chat = this.client.request("getChat", Map.of("chat_id", tgChatId));
        JsonNode type = chat.path("type");
        String chatType = type.path("@type").asString("");

        if ("chatTypePrivate".equals(chatType)) {
            // A private chat's only counterpart is named directly in the chat's own `type` --
            // there is no separate members call for it the way getSupergroupMembers is for a
            // supergroup. Real-account testing found this case entirely unhandled: treating it
            // like a type with no member list at all left the participant table permanently
            // empty for any 1-on-1 chat, so there was never a row to record that person's
            // consent against, and the chat could never leave AWAITING_CONSENT. Naming that one
            // counterpart directly is itself the complete membership -- nothing paginated or
            // permission-gated can make it any more or less verified than this.
            Map<Long, RemoteParticipant> result = new LinkedHashMap<>();
            addParticipant(result, type.path("user_id").asLong());
            return RemoteMembership.complete(List.copyOf(result.values()));
        }
        if ("chatTypeBasicGroup".equals(chatType)) {
            // A plain (non-super) group has no separate paginated members call -- TDLib caps it
            // at 200 members and returns the whole roster in one shot via getBasicGroupFullInfo.
            // Real-account testing found this case entirely unwired: every basic group's
            // participant table stayed permanently empty, so ConsentCoverage.noParticipantsKnown()
            // never had anyone to grant consent against and the chat could never leave
            // AWAITING_CONSENT -- indistinguishable, from the operator's side, from "consent
            // cannot be granted at all" for that group.
            long basicGroupId = type.path("basic_group_id").asLong();
            Map<Long, RemoteParticipant> result = new LinkedHashMap<>();
            JsonNode info;
            try {
                info = this.client.request("getBasicGroupFullInfo", Map.of("basic_group_id", basicGroupId));
            }
            catch (TelegramPermissionException ex) {
                // Security-audit finding SEC-02: unlike the private-chat case above, this is a
                // source that refused to answer at all, not a confirmed-empty roster -- treating
                // it as complete would let consent coverage look satisfied for zero participants
                // when the truth is simply unknown.
                return RemoteMembership.incomplete(List.of());
            }
            for (JsonNode member : info.path("members")) {
                JsonNode senderId = member.path("member_id");
                if ("messageSenderUser".equals(senderId.path("@type").asString(""))) {
                    addParticipant(result, senderId.path("user_id").asLong());
                }
            }
            return RemoteMembership.complete(List.copyOf(result.values()));
        }
        if (!"chatTypeSupergroup".equals(chatType)) {
            // Any other chat type (e.g. a channel, handled separately -- see ChatSyncService)
            // exposes no member list this method could ever verify one way or the other; a
            // confirmed-empty, complete result for a type with no equivalent of "consent-
            // relevant membership" at all.
            return RemoteMembership.complete(List.of());
        }
        long supergroupId = type.path("supergroup_id").asLong();

        // Security-audit finding SEC-02: keyed by tgUserId, not a plain list, so a member
        // TDLib's offset-based pagination happens to return on two different pages (membership
        // can change mid-enumeration) is counted once rather than reported as departed and
        // rejoined, or double-counted against MAX_MEMBERS.
        Map<Long, RemoteParticipant> result = new LinkedHashMap<>();
        int offset = 0;
        while (result.size() < MAX_MEMBERS) {
            JsonNode members;
            try {
                members = this.client.request("getSupergroupMembers", Map.of(
                        "supergroup_id", supergroupId,
                        "filter", Map.of("@type", "supergroupMembersFilterRecent"),
                        "offset", offset,
                        "limit", MEMBER_PAGE_SIZE));
            }
            catch (TelegramPermissionException ex) {
                // Real-account testing found Telegram routinely refuses this call ("Member list
                // is inaccessible", code 400) for a supergroup where the connected account
                // lacks sufficient privilege to see membership -- a normal, common situation,
                // not a bug. Before this fix the exception propagated all the way out of
                // propose()/importParticipants(), crashing the entire "select this chat" action
                // instead of leaving the participant list empty, which already surfaces as
                // ConsentCoverage.noParticipantsKnown() and correctly blocks activation until
                // membership is somehow known. Security-audit finding SEC-02: reported as
                // incomplete, not complete-empty, for the same reason as the basic-group case
                // above -- this is a refusal to answer, not a verified empty roster.
                return RemoteMembership.incomplete(List.copyOf(result.values()));
            }
            int pageSize = 0;
            for (JsonNode member : members.path("members")) {
                pageSize++;
                JsonNode senderId = member.path("member_id");
                if ("messageSenderUser".equals(senderId.path("@type").asString(""))) {
                    addParticipant(result, senderId.path("user_id").asLong());
                }
            }
            if (pageSize < MEMBER_PAGE_SIZE) {
                // The source itself ran out of members before the safety cap did: genuinely
                // everyone TDLib knows about is now in `result`.
                return RemoteMembership.complete(List.copyOf(result.values()));
            }
            offset += MEMBER_PAGE_SIZE;
        }
        // Security-audit finding SEC-02: the loop above exited because MAX_MEMBERS was reached,
        // not because the source ran out of members -- there may be more we never asked for.
        // Reporting this the same as a fully enumerated chat would let consent coverage look
        // satisfied for only the members returned while others remain unconsulted, and would
        // have markLeft() wrongly report everyone beyond the cap as having departed.
        return RemoteMembership.incomplete(List.copyOf(result.values()));
    }

    private void addParticipant(Map<Long, RemoteParticipant> result, long userId) {
        JsonNode user = this.client.request("getUser", Map.of("user_id", userId));
        String name = (user.path("first_name").asString("") + " " + user.path("last_name").asString("")).trim();
        boolean bot = "userTypeBot".equals(user.path("type").path("@type").asString(""));
        result.put(userId, new RemoteParticipant(userId, name.isEmpty() ? String.valueOf(userId) : name, bot));
    }

    @Override
    public RemoteMessagePage fetchMessages(ConnectionRef ref, long tgChatId, MessageWindow window) {
        requireCurrentOwner(ref);
        int limit = Math.min(window.limit(), MAX_HISTORY_BATCH);
        // getChatHistory only ever walks backwards from from_message_id (0 means "from the
        // newest"), so reaching a specific afterMessageId boundary -- or the start of the
        // approved window -- can take several raw pages. This loop is that traversal; see the
        // class-level MAX_HISTORY_RAW_PAGES_PER_CALL javadoc for why it is capped rather than
        // unbounded (security-audit finding F12).
        long cursor = window.beforeMessageId() == null ? 0L : window.beforeMessageId();
        List<RemoteMessage> matched = new ArrayList<>();
        boolean reachedBoundary = false;
        boolean sourceExhausted = false;

        for (int page = 0; page < MAX_HISTORY_RAW_PAGES_PER_CALL; page++) {
            JsonNode response = this.client.request("getChatHistory", Map.of(
                    "chat_id", tgChatId,
                    "from_message_id", cursor,
                    "offset", 0,
                    "limit", limit,
                    "only_local", false));
            JsonNode rawMessages = response.path("messages");
            int rawCount = rawMessages.size();
            if (rawCount == 0) {
                sourceExhausted = true;
                break;
            }

            long oldestIdThisPage = Long.MAX_VALUE;
            for (JsonNode rawMessage : rawMessages) {
                RemoteMessage mapped = toRemoteMessage(rawMessage);
                oldestIdThisPage = Math.min(oldestIdThisPage, mapped.messageId());
                if (window.afterMessageId() != null && mapped.messageId() <= window.afterMessageId()) {
                    // Everything at or below the incremental catch-up target has now been
                    // reached; nothing further back can be new.
                    reachedBoundary = true;
                    continue;
                }
                if (mapped.sentAt().isBefore(window.from())) {
                    // Walked past the earliest date this window will ever accept (the approved
                    // range or retention floor); nothing further back can match either.
                    reachedBoundary = true;
                    continue;
                }
                if (!mapped.sentAt().isAfter(window.to())) {
                    matched.add(mapped);
                }
                // Newer than window.to(): not a match, but not a stopping condition either --
                // this is exactly the "backlog accumulated past the approved window" case F12
                // describes, and older, in-window messages may still be further back.
            }
            cursor = oldestIdThisPage;
            if (reachedBoundary) {
                break;
            }
            if (rawCount < limit) {
                sourceExhausted = true;
                break;
            }
            if (matched.size() >= limit) {
                break;
            }
        }

        matched.sort(java.util.Comparator.comparingLong(RemoteMessage::messageId));
        boolean hasMore = !reachedBoundary && !sourceExhausted;
        return new RemoteMessagePage(List.copyOf(matched), hasMore, hasMore ? cursor : null);
    }

    RemoteMessage toRemoteMessage(JsonNode message) {
        JsonNode sender = message.path("sender_id");
        Long senderId = "messageSenderUser".equals(sender.path("@type").asString(""))
                ? sender.path("user_id").asLong() : null;
        long editDate = message.path("edit_date").asLong(0L);
        return new RemoteMessage(
                message.path("id").asLong(),
                senderId,
                senderId == null ? null : String.valueOf(senderId),
                Instant.ofEpochSecond(message.path("date").asLong(0L)),
                editDate > 0 ? Instant.ofEpochSecond(editDate) : null,
                false,
                extractText(message.path("content")),
                message.path("reply_to").path("message_id").asLong(0L) == 0L
                        ? null : message.path("reply_to").path("message_id").asLong());
    }

    /**
     * Media, voice and files are never downloaded, transcribed or OCR'd in this MVP -- that
     * would need TDLib file downloads, its own storage/retention/consent handling, and likely a
     * vision- or audio-capable model, all out of scope here (see docs/production-
     * prerequisites.md). But real-account testing found a photo, document, voice note or other
     * non-text message contributed a completely empty body: not "we can't read the content" but
     * literally nothing, indistinguishable from an empty text message and invisible to Claude
     * even as "something was shared here". A short bracketed label (with the filename, for a
     * document, or the caption, where the message carries one) at least says a file or photo
     * exists and roughly what kind, without claiming to have looked inside it.
     */
    static String extractText(JsonNode content) {
        String type = content.path("@type").asString("");
        if ("messageText".equals(type)) {
            return content.path("text").path("text").asString("");
        }
        String label = mediaLabel(content, type);
        if (label == null) {
            return "";
        }
        String caption = content.path("caption").path("text").asString("");
        return caption.isEmpty() ? label : label + ": " + caption;
    }

    private static String mediaLabel(JsonNode content, String type) {
        return switch (type) {
            case "messagePhoto" -> "[Photo]";
            case "messageVideo" -> "[Video]";
            case "messageVideoNote" -> "[Video message]";
            case "messageVoiceNote" -> "[Voice message]";
            case "messageAnimation" -> "[GIF]";
            case "messageSticker" -> {
                String emoji = content.path("sticker").path("emoji").asString("");
                yield emoji.isEmpty() ? "[Sticker]" : "[Sticker " + emoji + "]";
            }
            case "messageAudio" -> {
                JsonNode audio = content.path("audio");
                String title = audio.path("title").asString("");
                String performer = audio.path("performer").asString("");
                String described = String.join(" - ", java.util.stream.Stream.of(performer, title)
                        .filter(s -> !s.isEmpty()).toList());
                yield described.isEmpty() ? "[Audio]" : "[Audio: " + described + "]";
            }
            case "messageDocument" -> {
                String fileName = content.path("document").path("file_name").asString("");
                yield fileName.isEmpty() ? "[File]" : "[File: " + fileName + "]";
            }
            case "messageLocation" -> "[Location]";
            case "messageContact" -> "[Contact]";
            case "messagePoll" -> "[Poll]";
            default -> null;
        };
    }

    @Override
    public List<RemoteMessage> fetchRevisions(ConnectionRef ref, long tgChatId, Instant changedSince, int limit) {
        requireCurrentOwner(ref);
        // Security-audit finding F08: drains the buffer handleMessageContentUpdate /
        // handleDeleteMessagesUpdate filled from TDLib's own pushed updates -- see the
        // pendingRevisions field javadoc. changedSince is unused here on purpose: unlike the
        // mock adapter's query-a-log implementation, this buffer only ever holds revisions
        // pushed since the last successful drain, so anything in it is already "since last
        // sync" by construction; there is nothing older left to filter out.
        Deque<RemoteMessage> queue = this.pendingRevisions.get(tgChatId);
        if (queue == null) {
            return List.of();
        }
        List<RemoteMessage> drained = new ArrayList<>();
        RemoteMessage next;
        while (drained.size() < limit && (next = queue.pollFirst()) != null) {
            drained.add(next);
        }
        return List.copyOf(drained);
    }

    @Override
    public Optional<String> messageLink(RemoteChat chat, long tgMessageId) {
        // Real-account testing found getMessageLink answering "Message links are available only
        // for messages in supergroups and channel chats" (code 400) for every single message
        // synced from a private chat or a basic (non-super) group -- a doomed call, not a
        // transient failure, per TDLib's own documented restriction on this method. The
        // TelegramPermissionException catch below already degrades this correctly to
        // Optional.empty(), but repeating the call and logging a WARN for every message in
        // every such chat is pure noise once the outcome is guaranteed in advance.
        if (!"SUPERGROUP".equals(chat.chatType()) && !"CHANNEL".equals(chat.chatType())) {
            return Optional.empty();
        }
        try {
            JsonNode link = this.client.request("getMessageLink", Map.of(
                    "chat_id", chat.tgChatId(),
                    "message_id", tgMessageId,
                    "media_timestamp", 0,
                    "for_album", false,
                    "in_message_thread", false));
            // A private-chat link is not resolvable by anyone else, so we do not hand it out.
            if (!link.path("is_public").asBoolean(false)) {
                return Optional.empty();
            }
            String value = link.path("link").asString("");
            return value.isBlank() ? Optional.empty() : Optional.of(value);
        }
        catch (TelegramPermissionException ex) {
            return Optional.empty();
        }
    }

    private void requireRealAccessAuthorized() {
        if (!this.config.realAccountAccessAuthorized()) {
            throw new TelegramAuthException("Real Telegram account access is not authorized. "
                    + "Set tgmcp.telegram.real-account-access-authorized=true only with explicit permission.");
        }
    }
}
