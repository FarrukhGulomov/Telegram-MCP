package io.tgmcp.telegram.tdlib;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import io.tgmcp.config.AppProperties;
import io.tgmcp.telegram.gateway.ConnectionRef;
import io.tgmcp.telegram.gateway.LoginMethod;
import io.tgmcp.telegram.gateway.LoginStage;
import io.tgmcp.telegram.gateway.LoginState;
import io.tgmcp.telegram.gateway.MessageWindow;
import io.tgmcp.telegram.gateway.RemoteChat;
import io.tgmcp.telegram.gateway.RemoteMessage;
import io.tgmcp.telegram.gateway.RemoteMessagePage;
import io.tgmcp.telegram.gateway.RemoteParticipant;
import io.tgmcp.telegram.gateway.TelegramAdapterKind;
import io.tgmcp.telegram.gateway.TelegramAuthException;
import io.tgmcp.telegram.gateway.TelegramGateway;
import io.tgmcp.telegram.gateway.TelegramPermissionException;
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

    private static final int MAX_HISTORY_BATCH = 100;

    private final TdlibClient client;
    private final AppProperties.Telegram config;
    private final AtomicReference<LoginState> loginState =
            new AtomicReference<>(LoginState.of(LoginStage.DISCONNECTED, null));

    public TdlibTelegramGateway(TdlibClient client, AppProperties.Telegram config) {
        this.client = client;
        this.config = config;
        this.client.onUpdate(this::handleUpdate);
    }

    @Override
    public TelegramAdapterKind kind() {
        return TelegramAdapterKind.TDLIB;
    }

    // --- authorization ----------------------------------------------------------------

    private void handleUpdate(JsonNode update) {
        if (!"updateAuthorizationState".equals(update.path("@type").asString(""))) {
            return;
        }
        JsonNode state = update.path("authorization_state");
        this.loginState.set(mapAuthorizationState(state));
        if ("authorizationStateWaitTdlibParameters".equals(state.path("@type").asString(""))) {
            sendTdlibParameters();
        }
    }

    LoginState mapAuthorizationState(JsonNode state) {
        return switch (state.path("@type").asString("")) {
            case "authorizationStateWaitPhoneNumber" ->
                    LoginState.of(LoginStage.WAIT_PHONE_NUMBER, "Telegram is waiting for a phone number or QR login.");
            case "authorizationStateWaitCode" ->
                    LoginState.of(LoginStage.WAIT_CODE, "Telegram sent a login code.");
            case "authorizationStateWaitOtherDeviceConfirmation" ->
                    new LoginState(LoginStage.WAIT_QR_SCAN, state.path("link").asString(null), null, null,
                            "Scan this link from a Telegram app that is already signed in.");
            case "authorizationStateWaitPassword" ->
                    LoginState.of(LoginStage.WAIT_PASSWORD, "Two-factor authentication password required.");
            case "authorizationStateReady" -> LoginState.of(LoginStage.READY, "Connected.");
            case "authorizationStateLoggingOut", "authorizationStateClosing", "authorizationStateClosed" ->
                    LoginState.of(LoginStage.DISCONNECTED, "Session closed.");
            case "authorizationStateWaitTdlibParameters" ->
                    LoginState.of(LoginStage.DISCONNECTED, "Initialising Telegram client.");
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
        this.client.request("checkAuthenticationCode", Map.of("code", code));
        return this.loginState.get();
    }

    @Override
    public LoginState submitPassword(ConnectionRef ref, String password) {
        this.client.request("checkAuthenticationPassword", Map.of("password", password));
        return this.loginState.get();
    }

    @Override
    public LoginState currentState(ConnectionRef ref) {
        return this.loginState.get();
    }

    @Override
    public void disconnect(ConnectionRef ref) {
        // logOut ends the Telegram-side session for this client; close only detaches locally.
        this.client.request("logOut", Map.of());
        this.loginState.set(LoginState.of(LoginStage.DISCONNECTED, "Signed out."));
    }

    // --- read-only data ---------------------------------------------------------------

    @Override
    public List<RemoteChat> listAvailableChats(ConnectionRef ref) {
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
    public List<RemoteParticipant> listParticipants(ConnectionRef ref, long tgChatId) {
        JsonNode chat = this.client.request("getChat", Map.of("chat_id", tgChatId));
        JsonNode type = chat.path("type");
        if (!"chatTypeSupergroup".equals(type.path("@type").asString(""))) {
            // Basic groups and private chats do not expose a member list through this call.
            return List.of();
        }
        JsonNode members = this.client.request("getSupergroupMembers", Map.of(
                "supergroup_id", type.path("supergroup_id").asLong(),
                "filter", Map.of("@type", "supergroupMembersFilterRecent"),
                "offset", 0,
                "limit", 200));

        List<RemoteParticipant> result = new ArrayList<>();
        for (JsonNode member : members.path("members")) {
            JsonNode senderId = member.path("member_id");
            if (!"messageSenderUser".equals(senderId.path("@type").asString(""))) {
                continue;
            }
            long userId = senderId.path("user_id").asLong();
            JsonNode user = this.client.request("getUser", Map.of("user_id", userId));
            String name = (user.path("first_name").asString("") + " " + user.path("last_name").asString("")).trim();
            boolean bot = "userTypeBot".equals(user.path("type").path("@type").asString(""));
            result.add(new RemoteParticipant(userId, name.isEmpty() ? String.valueOf(userId) : name, bot));
        }
        return List.copyOf(result);
    }

    @Override
    public RemoteMessagePage fetchMessages(ConnectionRef ref, long tgChatId, MessageWindow window) {
        int limit = Math.min(window.limit(), MAX_HISTORY_BATCH);
        // getChatHistory walks backwards from from_message_id; 0 means "from the newest".
        long fromMessageId = window.beforeMessageId() == null ? 0L : window.beforeMessageId();
        JsonNode response = this.client.request("getChatHistory", Map.of(
                "chat_id", tgChatId,
                "from_message_id", fromMessageId,
                "offset", 0,
                "limit", limit,
                "only_local", false));

        List<RemoteMessage> messages = new ArrayList<>();
        for (JsonNode message : response.path("messages")) {
            RemoteMessage mapped = toRemoteMessage(message);
            if (mapped.sentAt().isBefore(window.from()) || mapped.sentAt().isAfter(window.to())) {
                continue;
            }
            if (window.afterMessageId() != null && mapped.messageId() <= window.afterMessageId()) {
                continue;
            }
            messages.add(mapped);
        }
        messages.sort(java.util.Comparator.comparingLong(RemoteMessage::messageId));
        boolean hasMore = response.path("messages").size() >= limit;
        return new RemoteMessagePage(List.copyOf(messages), hasMore);
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
        // TDLib delivers edits and deletions as updateMessageContent / updateDeleteMessages
        // rather than as a pollable query. Those updates are applied by the sync worker via
        // TdlibUpdateBuffer; there is no historical revision query to make here.
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
