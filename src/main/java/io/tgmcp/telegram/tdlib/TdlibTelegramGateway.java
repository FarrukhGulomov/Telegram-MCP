package io.tgmcp.telegram.tdlib;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        if (!"updateAuthorizationState".equals(update.path("@type").asString(""))) {
            return;
        }
        JsonNode state = update.path("authorization_state");
        LoginState mapped = mapAuthorizationState(state);
        // Security-audit finding F13: authorizationStateReady on its own carries no account
        // identity. Fetching it here -- rather than leaving tgUserId/tgUsername null until
        // some later request happens to populate them -- means whatever this update notifies
        // listeners with (see below) is already complete. Safe to call now rather than
        // deadlocking: security-audit finding F11 moved listener dispatch off the receive
        // thread specifically so a listener may call request() itself.
        if (mapped.stage() == LoginStage.READY) {
            mapped = withAccountIdentity(mapped);
        }
        this.loginState.set(mapped);
        if ("authorizationStateWaitTdlibParameters".equals(state.path("@type").asString(""))) {
            sendTdlibParameters();
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
    }

    // --- read-only data ---------------------------------------------------------------

    @Override
    public List<RemoteChat> listAvailableChats(ConnectionRef ref) {
        requireCurrentOwner(ref);
        // loadChats populates the local chat list; getChats then reports it.
        try {
            this.client.request("loadChats", Map.of("chat_list", Map.of("@type", "chatListMain"), "limit", 200));
        }
        catch (TelegramPermissionException ignored) {
            // TDLib answers 404 when the list is already fully loaded; that is not a failure.
        }
        JsonNode chats = this.client.request("getChats",
                Map.of("chat_list", Map.of("@type", "chatListMain"), "limit", 200));

        List<RemoteChat> result = new ArrayList<>();
        for (JsonNode idNode : chats.path("chat_ids")) {
            JsonNode chat = this.client.request("getChat", Map.of("chat_id", idNode.asLong()));
            result.add(toRemoteChat(chat));
        }
        return List.copyOf(result);
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
        if (!"chatTypeSupergroup".equals(type.path("@type").asString(""))) {
            // Basic groups and private chats do not expose a member list through this call --
            // confirmed empty, not unverifiable, so this is complete as far as this method can
            // ever observe for these chat types.
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
            JsonNode members = this.client.request("getSupergroupMembers", Map.of(
                    "supergroup_id", supergroupId,
                    "filter", Map.of("@type", "supergroupMembersFilterRecent"),
                    "offset", offset,
                    "limit", MEMBER_PAGE_SIZE));
            int pageSize = 0;
            for (JsonNode member : members.path("members")) {
                pageSize++;
                addParticipant(result, member);
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

    private void addParticipant(Map<Long, RemoteParticipant> result, JsonNode member) {
        JsonNode senderId = member.path("member_id");
        if (!"messageSenderUser".equals(senderId.path("@type").asString(""))) {
            return;
        }
        long userId = senderId.path("user_id").asLong();
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
     * Only textual content is in scope for the MVP: media, voice and files are neither
     * downloaded nor transcribed, so a non-text message contributes an empty body.
     */
    static String extractText(JsonNode content) {
        return switch (content.path("@type").asString("")) {
            case "messageText" -> content.path("text").path("text").asString("");
            case "messagePhoto", "messageVideo", "messageDocument", "messageAudio", "messageAnimation" ->
                    content.path("caption").path("text").asString("");
            default -> "";
        };
    }

    @Override
    public List<RemoteMessage> fetchRevisions(ConnectionRef ref, long tgChatId, Instant changedSince, int limit) {
        // Security-audit finding F08: TDLib delivers edits and deletions as pushed
        // updateMessageContent / updateDeleteMessages events, not as a pollable history query,
        // so there is no request this method can make to answer "what changed since X" the way
        // the MOCK adapter's fetchRevisions does. Nothing in this class currently listens for
        // those update types and turns them into RemoteMessage revisions (that is a distinct,
        // larger piece of work -- routing td_receive() updates into the sync worker under the
        // adapter's per-connection locking, see security-audit finding F11 -- and out of scope
        // for this pass). Returning an empty list here is therefore not "nothing changed": it is
        // an honest "this adapter cannot see edits or deletions yet". See the startup warning in
        // TelegramConfiguration and docs/production-prerequisites.md, both of which call this out
        // as a blocking gap for any real deployment.
        return List.of();
    }

    @Override
    public Optional<String> messageLink(RemoteChat chat, long tgMessageId) {
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
