package io.tgmcp.telegram.tdlib;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import io.tgmcp.common.KeyMaterial;
import io.tgmcp.config.AppProperties;
import io.tgmcp.telegram.gateway.ConnectionRef;
import io.tgmcp.telegram.gateway.DownloadedMedia;
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
import io.tgmcp.telegram.gateway.TelegramGatewayException;
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
    /**
     * Safety cap on how many {@code loadChats} calls one warmChatList() will make. TDLib's own
     * documentation for {@code loadChats} says only that it loads "more chats" into the runtime
     * chat list, with no guarantee the whole list is loaded in one call -- for an account with
     * many chats, calling it exactly once (as this method used to) could leave chats beyond
     * whatever partial batch that call loaded permanently unreachable for the rest of this TDLib
     * session: every subsequent getChat/getChatHistory/getSupergroupMembers on one of them fails
     * with code 400 "Chat not found", which TdlibErrors.translate() maps to a retryable
     * TelegramTransientException that then retries forever without ever succeeding. This cap
     * exists only to bound the work one call may do in the (believed impossible in practice, per
     * TDLib's own semantics) case where the list never reports itself fully loaded via the 404
     * below.
     */
    static final int MAX_LOAD_CHATS_CALLS = 50;
    /**
     * Safety cap on how many chats one {@code getChats} call will ask TDLib to return. Unlike
     * {@code loadChats}, {@code getChats} is a purely local read of whatever is already loaded
     * into the runtime chat list -- it never triggers loading itself, and its own {@code limit}
     * argument is a hard cap on the result, not a hint. warmChatList() used to pass the same
     * fixed 200 here that it used per {@code loadChats} chunk, so even after the fix above fully
     * loaded every chat into TDLib's runtime list, an account with more than 200 chats still had
     * this final read truncated to (at most) its 200 most-recently-active chats -- silently
     * dropping any account discoverChats() would otherwise have found beyond that, from the
     * chat picker onward. This must be large enough to cover the whole list once loaded.
     */
    static final int MAX_CHATS = 10_000;

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
        // Confirmed from live production logs: TelegramConnectionService.reclaimOwnershipOnStartup
        // calls this at application boot, for a connection whose persisted row was already READY
        // before the restart -- but claiming ownership here races the async update-dispatch
        // thread that would otherwise send setTdlibParameters once it observes TDLib's own
        // authorizationStateWaitTdlibParameters push, which (being a genuine network round trip
        // to Telegram) can easily take longer than the few seconds it takes Spring to finish
        // starting up. A request that reaches this newly "owned" ref before that push has been
        // processed -- an operator's page load, or the sync scheduler's very first tick -- passed
        // requireCurrentOwner only to have TDLib itself reject it with "Initialization parameters
        // are needed", surfacing as an unhandled error and leaving every newly activated chat
        // permanently stuck at "never synced". Ensuring it here too, exactly like beginLogin does
        // for the same race on a fresh login, closes it: whichever of this, beginLogin, or the
        // async push runs first actually sends the parameters, and the rest are no-ops.
        ensureTdlibParametersSent();
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
                    null, null, null));
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
        if ("authorizationStateClosed".equals(state.path("@type").asString(""))) {
            // Confirmed from live production logs: releases whichever disconnect() call (if any)
            // is currently waiting on this exact signal -- see its own javadoc for why that wait
            // exists. A stray countDown() with nobody waiting (a Closed transition TDLib reached
            // on its own, e.g. a remote logout, never through our own disconnect()) is harmless.
            CountDownLatch signal = this.closedSignal;
            if (signal != null) {
                signal.countDown();
            }
        }
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
            // TDLib's own phone_number is always bare digits, never "+"-prefixed -- normalize it
            // to the same "+<digits>" form PreAuthLoginService.normalizePhoneNumber() produces
            // from what a visitor types, so a returning account's own findByPhoneNumber lookup
            // (an exact string match) keeps matching after this identity is written back to it.
            String phoneNumber = me.path("phone_number").asString(null);
            if (phoneNumber != null && !phoneNumber.isBlank() && !phoneNumber.startsWith("+")) {
                phoneNumber = "+" + phoneNumber;
            }
            return new LoginState(state.stage(), state.qrLoginLink(), tgUserId, username, phoneNumber,
                    state.message(), state.resendAvailableInSeconds(), state.nextCodeChannelKey());
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
            case "authorizationStateWaitCode" -> waitCodeLoginState(state);
            case "authorizationStateWaitOtherDeviceConfirmation" ->
                    new LoginState(LoginStage.WAIT_QR_SCAN, state.path("link").asString(null), null, null, null,
                            "login.tdlib.waitQrScan", null, null);
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
     * Confirmed from live production logs: a visitor who never receives a login code had no way
     * to tell, from the previous one-size-fits-all "Telegram sent a login code" hint, that
     * Telegram may have delivered it somewhere other than SMS -- most commonly as a message
     * inside the Telegram app on another device already signed into this same account, which a
     * visitor testing with a number that has no other active Telegram session (or who simply
     * isn't checking that device) will never see arrive at all. TDLib's own {@code
     * authorizationStateWaitCode} already carries exactly this as {@code code_info.type}; this
     * was previously discarded entirely. Surfacing it as a distinct, channel-specific message
     * key instead of the generic one tells the visitor where to actually look, rather than
     * leaving them to conclude -- wrongly -- that no code was ever sent.
     *
     * <p>Confirmed from a real follow-up report: knowing the wrong channel was used is not
     * enough on its own -- a visitor stuck on {@code authenticationCodeTypeTelegramMessage} with
     * no other signed-in device has no way to get Telegram to try a different one, since Telegram
     * itself (not this app) decides the very first channel, and {@code resendAuthenticationCode}
     * is refused ("Authentication code can't be resend") until {@code code_info.timeout} has
     * elapsed. TDLib already names what will happen after that wait -- {@code code_info.next_type}
     * -- which was, like {@code type} before it, previously discarded. Surfacing both turns "code
     * never arrives, no idea why or what to do" into an actionable instruction: wait N seconds,
     * then resend, and it will come by a named, different channel.
     */
    private static LoginState waitCodeLoginState(JsonNode state) {
        JsonNode codeInfo = state.path("code_info");
        String channel = codeChannelKey(codeInfo.path("type"));
        String messageKey = channel == null ? "login.tdlib.waitCode" : "login.tdlib.waitCode." + channel;
        String nextChannel = codeChannelKey(codeInfo.path("next_type"));
        Integer timeoutSeconds = nextChannel == null || !codeInfo.has("timeout")
                ? null : codeInfo.path("timeout").asInt();
        // No PII here (no phone number, no code) -- just which of TDLib's own named channels it
        // is offering. Logged because the alternative, asking a real visitor to transcribe exact
        // UI copy back to us, has repeatedly been too slow and too lossy to actually root-cause
        // reports of "no code arrived" against what Telegram itself is telling this session.
        log.info("TDLib code delivery: channel={} nextChannel={} timeoutSeconds={} rawType={} rawNextType={}",
                channel, nextChannel, timeoutSeconds, codeInfo.path("type").path("@type").asString("none"),
                codeInfo.path("next_type").path("@type").asString("none"));
        return new LoginState(LoginStage.WAIT_CODE, null, null, null, null, messageKey, timeoutSeconds, nextChannel);
    }

    private static String codeChannelKey(JsonNode authenticationCodeType) {
        return switch (authenticationCodeType.path("@type").asString("")) {
            case "authenticationCodeTypeSms" -> "sms";
            case "authenticationCodeTypeTelegramMessage" -> "telegramApp";
            case "authenticationCodeTypeCall" -> "call";
            case "authenticationCodeTypeFlashCall" -> "flashCall";
            case "authenticationCodeTypeMissedCall" -> "missedCall";
            default -> null;
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
        try {
            this.client.request("setTdlibParameters", params);
        }
        catch (TelegramTransientException ex) {
            // Confirmed from live production logs: this call can lose a race against another
            // caller of ensureTdlibParametersSent() for the very same client -- disconnect()
            // marks a fresh epoch proactively, anticipating a later authorizationStateWait
            // TdlibParameters push that a concurrent beginLogin() (HTTP thread) can also react
            // to and send first. TDLib then rejects this now-redundant send as "Unexpected
            // setTdlibParameters" (TdlibErrors' PARAMETERS_ALREADY_SET_MESSAGE) -- meaning the
            // client already has valid parameters, exactly what this method set out to ensure.
            // That is success, not failure, for every caller (beginLogin, onStateChange, and the
            // authorizationStateWaitTdlibParameters push handler alike), so it must not
            // propagate -- unlike any other TelegramTransientException, which a caller may
            // reasonably want to retry or report.
            if (!TdlibErrors.PARAMETERS_ALREADY_SET_MESSAGE.equals(ex.getMessage())) {
                throw ex;
            }
        }
    }

    @Override
    public LoginState beginLogin(ConnectionRef ref, LoginMethod method, String phoneNumber) {
        requireRealAccessAuthorized();
        // Security-audit follow-up on e514a6d: disconnect() already blocks (bounded by
        // DISCONNECT_TEARDOWN_TIMEOUT) for authorizationStateClosed before returning, but on a
        // genuinely slow teardown it can still give up and return with the client not actually
        // closed yet -- previously a silent "proceeding anyway" warning. Sending
        // setAuthenticationPhoneNumber/requestQrCodeAuthentication into that still-tearing-down
        // client is exactly the original race (TDLib rejects it as unexpected, no code is ever
        // sent, nothing tells the visitor why): fail loud here instead of repeating it silently.
        // The common case -- no logout in flight, or one that already reached
        // authorizationStateClosed -- costs nothing (closedSignal is null, or already counted
        // down to zero).
        CountDownLatch stillClosing = this.closedSignal;
        if (stillClosing != null && stillClosing.getCount() > 0) {
            throw new TelegramTransientException("Previous Telegram session has not finished closing yet");
        }
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
        if (this.loginState.get().stage() != LoginStage.WAIT_CODE) {
            // Live-bug: the same class of race already guarded against in resendCode below --
            // authorizationStateReady (or any further transition) can arrive asynchronously,
            // after this method's own synchronous return but before a caller's next read
            // observes it. A second submission racing that arrival must not resend an
            // already-superseded checkAuthenticationCode call: TDLib correctly rejects it as
            // unexpected (code 400) since no code is pending any more, and that rejection used
            // to propagate straight out as a hard, unrecoverable error on a login step that had,
            // in fact, already moved on.
            return this.loginState.get();
        }
        this.client.request("checkAuthenticationCode", Map.of("code", code));
        return this.loginState.get();
    }

    @Override
    public LoginState resendCode(ConnectionRef ref) {
        requireCurrentOwner(ref);
        if (this.loginState.get().stage() != LoginStage.WAIT_CODE) {
            return this.loginState.get();
        }
        this.client.request("resendAuthenticationCode", Map.of());
        // A distinct message from plain "waiting for a code" (login.tdlib.waitCode) so the
        // operator gets confirmation the resend actually happened, not just the same static
        // copy they were already looking at.
        LoginState state = LoginState.of(LoginStage.WAIT_CODE, "login.tdlib.codeResent");
        this.loginState.set(state);
        return state;
    }

    @Override
    public LoginState submitPassword(ConnectionRef ref, String password) {
        requireCurrentOwner(ref);
        if (this.loginState.get().stage() != LoginStage.WAIT_PASSWORD) {
            // Live report, reproduced from production logs: a QR/shadow login's
            // authorizationStateReady push can arrive asynchronously right after the first
            // checkAuthenticationPassword call already succeeded, but before the caller's
            // synchronous return (and the connect page's next status poll) observed it -- so the
            // UI re-prompted for the two-factor password a second time with no error shown.
            // Submitting it then resent checkAuthenticationPassword even though authentication
            // had already completed; TDLib correctly rejects that as unexpected (code 400),
            // which used to propagate straight out as a hard, unrecoverable error on a login
            // that had, in fact, already succeeded.
            return this.loginState.get();
        }
        this.client.request("checkAuthenticationPassword", Map.of("password", password));
        return this.loginState.get();
    }

    @Override
    public LoginState currentState(ConnectionRef ref) {
        requireCurrentOwner(ref);
        return this.loginState.get();
    }

    /**
     * Bounds how long {@link #disconnect} will block waiting for the real teardown to finish
     * (see that method's own doc) before giving up and returning anyway -- real production
     * logout round trips observed so far finish in well under two seconds, so this is a generous
     * safety margin, not a tuned deadline.
     */
    private static final Duration DISCONNECT_TEARDOWN_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Confirmed from live production logs (the exact "Call to setAuthenticationPhoneNumber
     * unexpected" / "Unexpected setTdlibParameters" failures reported against
     * telegram-mcp-tdlib-test): completed by {@link #handleAuthorizationStateUpdate} the moment
     * it observes {@code authorizationStateClosed} -- see {@link #disconnect}.
     */
    private volatile CountDownLatch closedSignal;

    @Override
    public void disconnect(ConnectionRef ref) {
        requireCurrentOwner(ref);
        // Confirmed from live production logs: logOut only *starts* tearing this client down --
        // TDLib announces the real teardown asynchronously as authorizationStateLoggingOut,
        // authorizationStateClosing, then authorizationStateClosed, well after this request's own
        // "Ok" response returns. Returning immediately after that response (as this method used
        // to) let a very next call into this same client -- historically, an ordinary
        // returning-account beginLogin, right after PreAuthLoginService disconnected the account's
        // old session as step one of signing back in -- reach it while it was still mid-teardown.
        // TDLib correctly rejected whichever of setTdlibParameters or setAuthenticationPhoneNumber
        // landed in that window as "unexpected", since the client was not actually in a state
        // that accepts either yet -- surfacing to the visitor as a login that appeared to work
        // (no error) but silently never sent a fresh code, indistinguishable from Telegram simply
        // not delivering one. Waiting here for the real, unambiguous completion signal
        // (authorizationStateClosed) closes that window: by the time this method returns, no
        // caller can land on a client that is still tearing down.
        CountDownLatch latch = new CountDownLatch(1);
        this.closedSignal = latch;
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
        // Belt-and-braces alongside the wait above: if the wait below times out (Closed never
        // arrives for some reason), a login attempted right after this disconnect must still
        // resend parameters rather than skip straight to requestQrCodeAuthentication/
        // setAuthenticationPhoneNumber, which TDLib would then reject with "Initialization
        // parameters are needed".
        this.tdlibParametersPending = true;
        try {
            boolean closed = latch.await(DISCONNECT_TEARDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!closed) {
                log.warn("Timed out waiting for authorizationStateClosed after logOut; proceeding anyway");
            }
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Security-audit finding H3: unlike {@link #disconnect}, which ends a session gracefully
     * through Telegram's own login state machine (and requires an already-claimed owner), this
     * is for a tenant that is being discarded outright -- {@link RoutedTdlibTelegramGateway}
     * calls it once it has already removed this instance from its own tenant map, so nothing
     * here needs to negotiate ownership or wait for TDLib's asynchronous teardown; it only needs
     * to stop this JVM holding onto the native client's resources (its receive-loop
     * registration, its executor, its pending request futures) for a tenant that no longer
     * exists at all.
     */
    void close() {
        this.client.close();
    }

    /** @see #databaseDirectory() */
    String databaseDirectory() {
        return this.config.databaseDirectory();
    }

    /**
     * Security-audit finding H1: this class represents exactly one native client (one {@code
     * ConnectionRef#routingKey()}'s worth of session state), so "move a session from one routing
     * key to another" is meaningless at this level -- it is {@link RoutedTdlibTelegramGateway},
     * which holds the map of routing keys to instances of this class, that actually performs a
     * promotion (by re-keying its map, not by asking an instance to do anything to itself). This
     * class is never itself exposed as the top-level {@code TelegramGateway} bean, so this method
     * is unreachable in practice; it exists only to satisfy the interface.
     */
    @Override
    public void promoteSession(ConnectionRef shadowRef, java.util.UUID targetRoutingKey) {
        throw new UnsupportedOperationException(
                "promoteSession is handled by RoutedTdlibTelegramGateway, not by a single client instance");
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
     *
     * <p>Calls {@code loadChats} repeatedly (bounded by {@link #MAX_LOAD_CHATS_CALLS}) rather
     * than exactly once -- a single call is not documented to load the *entire* list, only "more"
     * of it, so an account with many chats could otherwise be left with chats beyond that first
     * partial batch permanently unreachable for the rest of this session (see {@link
     * #MAX_LOAD_CHATS_CALLS}).
     */
    private JsonNode warmChatList() {
        for (int call = 0; call < MAX_LOAD_CHATS_CALLS; call++) {
            try {
                this.client.request("loadChats", Map.of("chat_list", Map.of("@type", "chatListMain"), "limit", 200));
            }
            catch (TelegramTransientException ignored) {
                // TdlibErrors.translate() has no specific mapping for code 404 (it is neither a
                // flood wait, an auth failure, a permission failure, nor a malformed request), so
                // it falls through to TelegramTransientException -- not TelegramPermissionException,
                // which this catch clause caught until real-account testing hit exactly this path
                // and found the "refresh chat list" button crashing every single time with that 404
                // propagating straight out to the generic error page, uncaught. The 404 means the
                // list is now fully loaded, so there is nothing more to gain by calling again.
                break;
            }
        }
        return this.client.request("getChats", Map.of("chat_list", Map.of("@type", "chatListMain"), "limit", MAX_CHATS));
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
            // External review finding: TDLib's own documentation for getChatHistory is explicit
            // that "the number of returned messages is chosen by TDLib and can be smaller than
            // the specified limit, even if the end of the list is not reached" -- a short page is
            // an internal performance choice, never a promise that nothing older exists. Treating
            // rawCount < limit as sourceExhausted (as this used to) could stop backfill early and
            // mark it complete while real, older messages were still sitting unfetched on
            // Telegram's side, permanently out of reach since a completed backfill never resumes.
            // The only response TDLib gives that unambiguously means "nothing more exists" is a
            // genuinely empty one, handled above; every other case here keeps paging (bounded, as
            // always, by MAX_HISTORY_RAW_PAGES_PER_CALL and hasMore/resumeCursor below).
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
        MediaRef media = extractMedia(message.path("content"));
        return new RemoteMessage(
                message.path("id").asLong(),
                senderId,
                senderId == null ? null : String.valueOf(senderId),
                Instant.ofEpochSecond(message.path("date").asLong(0L)),
                editDate > 0 ? Instant.ofEpochSecond(editDate) : null,
                false,
                extractText(message.path("content")),
                message.path("reply_to").path("message_id").asLong(0L) == 0L
                        ? null : message.path("reply_to").path("message_id").asLong(),
                media == null ? null : media.kind(),
                media == null ? null : media.remoteFileId());
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

    /** @see RemoteMessage#mediaKind() */
    private record MediaRef(String kind, String remoteFileId) {
    }

    /**
     * Resolves the durable TDLib file reference for a message's visual preview -- the photo
     * itself, or a video/video-note/animation's already-generated thumbnail frame (never the
     * full video: see {@link RemoteMessage#mediaKind()}'s javadoc for why). {@code null} for
     * every other content type, and also when the expected size/thumbnail field is missing
     * from what TDLib reported (better to have no preview than to store a broken reference).
     */
    private static MediaRef extractMedia(JsonNode content) {
        String type = content.path("@type").asString("");
        String remoteFileId = switch (type) {
            case "messagePhoto" -> bestPhotoSizeRemoteId(content.path("photo").path("sizes"));
            case "messageVideo" -> thumbnailRemoteId(content.path("video").path("thumbnail"));
            case "messageVideoNote" -> thumbnailRemoteId(content.path("video_note").path("thumbnail"));
            case "messageAnimation" -> thumbnailRemoteId(content.path("animation").path("thumbnail"));
            default -> null;
        };
        if (remoteFileId == null) {
            return null;
        }
        return new MediaRef("messagePhoto".equals(type) ? "PHOTO" : "VIDEO_THUMBNAIL", remoteFileId);
    }

    /** Prefers TDLib's "m" (medium, ~320px) size when present; falls back to whichever is first. */
    private static String bestPhotoSizeRemoteId(JsonNode sizes) {
        if (!sizes.isArray() || sizes.isEmpty()) {
            return null;
        }
        for (JsonNode size : sizes) {
            if ("m".equals(size.path("type").asString(""))) {
                String id = size.path("photo").path("remote").path("id").asString("");
                if (!id.isEmpty()) {
                    return id;
                }
            }
        }
        String id = sizes.get(0).path("photo").path("remote").path("id").asString("");
        return id.isEmpty() ? null : id;
    }

    private static String thumbnailRemoteId(JsonNode thumbnail) {
        String id = thumbnail.path("file").path("remote").path("id").asString("");
        return id.isEmpty() ? null : id;
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
    public Optional<String> messageLink(ConnectionRef ref, RemoteChat chat, long tgMessageId) {
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

    private static final Duration MEDIA_DOWNLOAD_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration MEDIA_DOWNLOAD_POLL_INTERVAL = Duration.ofMillis(250);
    /** Every visual preview this adapter ever fetches (a photo, or a video/animation's
     * already-generated thumbnail) is a JPEG -- Telegram never uses another format for either. */
    private static final String MEDIA_MIME_TYPE = "image/jpeg";

    @Override
    public Optional<DownloadedMedia> downloadMessageMedia(ConnectionRef ref, String mediaRemoteFileId) {
        requireCurrentOwner(ref);
        JsonNode file;
        try {
            file = this.client.request("getRemoteFile", Map.of("remote_file_id", mediaRemoteFileId));
        }
        catch (TelegramGatewayException ex) {
            // The reference no longer resolves -- deleted upstream, or simply too old. An
            // ordinary, expected outcome for old media, not a transient failure to retry.
            return Optional.empty();
        }
        int fileId = file.path("id").asInt();
        if (!file.path("local").path("is_downloading_completed").asBoolean(false)) {
            this.client.request("downloadFile", Map.of(
                    "file_id", fileId, "priority", 1, "offset", 0, "limit", 0, "synchronous", false));
            file = awaitDownloadCompletion(fileId);
            if (file == null) {
                return Optional.empty();
            }
        }
        String path = file.path("local").path("path").asString("");
        if (path.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new DownloadedMedia(Files.readAllBytes(Path.of(path)), MEDIA_MIME_TYPE));
        }
        catch (IOException ex) {
            return Optional.empty();
        }
        finally {
            // Security-audit finding: this call used to leave every downloaded photo/thumbnail
            // sitting in the TDLib files directory indefinitely -- get_message_media's own tool
            // description promises nothing is stored, and revoke/delete-data only ever removed
            // database rows, never this directory. TDLib's own deleteFile removes the local copy
            // (cancelling an in-flight download if one were somehow still running) without
            // touching the remote message itself; a failure here is logged, not propagated,
            // since the caller has already received the bytes it asked for and a stale file
            // remains a cleanliness gap, not a reason to fail this call.
            deleteLocalFileBestEffort(fileId);
        }
    }

    private void deleteLocalFileBestEffort(int fileId) {
        try {
            this.client.request("deleteFile", Map.of("file_id", fileId));
        }
        catch (TelegramGatewayException ex) {
            log.warn("Failed to delete local copy of downloaded file {} after serving it", fileId, ex);
        }
    }

    /** {@code null} once {@link #MEDIA_DOWNLOAD_TIMEOUT} elapses without the download finishing. */
    private JsonNode awaitDownloadCompletion(int fileId) {
        long deadline = System.nanoTime() + MEDIA_DOWNLOAD_TIMEOUT.toNanos();
        while (true) {
            JsonNode file = this.client.request("getFile", Map.of("file_id", fileId));
            if (file.path("local").path("is_downloading_completed").asBoolean(false)) {
                return file;
            }
            if (System.nanoTime() >= deadline) {
                return null;
            }
            try {
                Thread.sleep(MEDIA_DOWNLOAD_POLL_INTERVAL.toMillis());
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }
}
