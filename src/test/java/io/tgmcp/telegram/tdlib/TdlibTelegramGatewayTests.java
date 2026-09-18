package io.tgmcp.telegram.tdlib;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import io.tgmcp.config.AppProperties;
import io.tgmcp.telegram.gateway.ConnectionRef;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the real TDLib adapter's JSON translation against a scripted client.
 *
 * <p>This covers the part of the adapter that can be verified without a native library or a
 * Telegram account: the mapping between TDLib's documented {@code td_api} objects and this
 * application's domain types. The native transport itself is not exercised here — see
 * {@code docs/production-prerequisites.md}.
 */
class TdlibTelegramGatewayTests {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final ScriptedTdlibClient client = new ScriptedTdlibClient(this.mapper);
    private final AppProperties.Telegram config =
            new AppProperties.Telegram(TelegramAdapterKind.TDLIB, "12345", "hash", "./tdlib", true, true);
    private final TdlibTelegramGateway gateway =
            new TdlibTelegramGateway(this.client, this.config, "dGVzdC1zZXNzaW9uLWVuY3J5cHRpb24ta2V5LTMyYnl0ZXM=");

    private final ConnectionRef ref = new ConnectionRef(UUID.randomUUID(), UUID.randomUUID());

    private JsonNode json(String raw) {
        return this.mapper.readTree(raw);
    }

    @BeforeEach
    void claimOwnershipForTheDefaultRef() {
        // Security-audit finding SEC-01: every gateway method below now requires the caller's
        // ref to be the current owner of the one shared session (see TdlibTelegramGateway
        // .requireCurrentOwner). Ordinary per-chat behaviour tests are not about that guard, so
        // claim ownership here the same way a real login start would (TelegramConnectionService
        // .beginLogin calls onStateChange first, for the same reason). Tests exercising the
        // guard itself use a second, never-claimed ref instead -- see below.
        this.gateway.onStateChange(this.ref, state -> { });
    }

    @Test
    void mapsEveryAuthorizationStateItSupports() {
        assertThat(this.gateway.mapAuthorizationState(json("""
                {"@type":"authorizationStateWaitPhoneNumber"}""")).stage())
                .isEqualTo(LoginStage.WAIT_PHONE_NUMBER);
        assertThat(this.gateway.mapAuthorizationState(json("""
                {"@type":"authorizationStateWaitCode"}""")).stage()).isEqualTo(LoginStage.WAIT_CODE);
        assertThat(this.gateway.mapAuthorizationState(json("""
                {"@type":"authorizationStateWaitPassword","password_hint":"hint"}""")).stage())
                .isEqualTo(LoginStage.WAIT_PASSWORD);
        assertThat(this.gateway.mapAuthorizationState(json("""
                {"@type":"authorizationStateReady"}""")).stage()).isEqualTo(LoginStage.READY);
        assertThat(this.gateway.mapAuthorizationState(json("""
                {"@type":"authorizationStateClosed"}""")).stage()).isEqualTo(LoginStage.DISCONNECTED);
    }

    @Test
    void exposesTheQrLoginLinkFromWaitOtherDeviceConfirmation() {
        var state = this.gateway.mapAuthorizationState(json("""
                {"@type":"authorizationStateWaitOtherDeviceConfirmation","link":"tg://login?token=abc"}"""));

        assertThat(state.stage()).isEqualTo(LoginStage.WAIT_QR_SCAN);
        assertThat(state.qrLoginLink()).isEqualTo("tg://login?token=abc");
    }

    @Test
    void reportsAnUnknownAuthorizationStateAsAnErrorRatherThanGuessing() {
        assertThat(this.gateway.mapAuthorizationState(json("""
                {"@type":"authorizationStateWaitEmailAddress"}""")).stage()).isEqualTo(LoginStage.ERROR);
    }

    @Test
    void reachingReadyFetchesAccountIdentityAndNotifiesTheRegisteredListener() {
        // Security-audit finding F13: authorizationStateReady on its own carries no account
        // identity, and arrives as a push update -- asynchronously, after whatever
        // request/response call started the login (in practice, well after a QR code was
        // scanned on another device). A registered listener must see the fully-identified
        // state the moment it happens, not just whenever the UI next happens to poll.
        List<LoginState> notifications = new ArrayList<>();
        this.gateway.onStateChange(this.ref, notifications::add);

        this.client.enqueue("""
                {"@type":"user","id":777,"usernames":{"editable_username":"real_owner"}}""");
        // Reaching READY also warms the runtime chat list (see TdlibTelegramGateway
        // .warmChatList()), so this test must script that pair of calls too.
        this.client.enqueue("{\"@type\":\"ok\"}");
        this.client.enqueue("""
                {"@type":"chats","chat_ids":[]}""");
        this.client.pushUpdate("""
                {"@type":"updateAuthorizationState",
                 "authorization_state":{"@type":"authorizationStateReady"}}""");

        assertThat(notifications).hasSize(1);
        LoginState notified = notifications.get(0);
        assertThat(notified.stage()).isEqualTo(LoginStage.READY);
        assertThat(notified.tgUserId()).isEqualTo(777L);
        assertThat(notified.tgUsername()).isEqualTo("real_owner");
        assertThat(this.gateway.currentState(this.ref).tgUserId())
                .as("the identity must also be reflected in currentState(), not only the notification")
                .isEqualTo(777L);
    }

    @Test
    void stillNotifiesTheListenerWhenReadyIdentityCannotBeFetched() {
        List<LoginState> notifications = new ArrayList<>();
        this.gateway.onStateChange(this.ref, notifications::add);

        this.client.enqueueError(500, "INTERNAL");
        // Reaching READY also warms the runtime chat list (see TdlibTelegramGateway
        // .warmChatList()), independently of whether the identity lookup above succeeded.
        this.client.enqueue("{\"@type\":\"ok\"}");
        this.client.enqueue("""
                {"@type":"chats","chat_ids":[]}""");
        this.client.pushUpdate("""
                {"@type":"updateAuthorizationState",
                 "authorization_state":{"@type":"authorizationStateReady"}}""");

        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).stage())
                .as("a failed identity lookup must not swallow the READY transition itself")
                .isEqualTo(LoginStage.READY);
        assertThat(notifications.get(0).tgUserId()).isNull();
    }

    @Test
    void aFailedChatListWarmUpAlsoDoesNotSwallowTheReadyTransition() {
        // Symmetric to stillNotifiesTheListenerWhenReadyIdentityCannotBeFetched above, but for
        // the other best-effort call reaching READY makes: warmChatList() failing must not stop
        // the READY state itself from being recorded and notified.
        List<LoginState> notifications = new ArrayList<>();
        this.gateway.onStateChange(this.ref, notifications::add);

        this.client.enqueue("""
                {"@type":"user","id":777,"usernames":{"editable_username":"real_owner"}}""");
        // loadChats succeeds, but getChats -- the one call in warmChatList() not guarded by its
        // own catch, since only loadChats' documented "already loaded" 404 is expected -- fails.
        this.client.enqueue("{\"@type\":\"ok\"}");
        this.client.enqueueError(500, "INTERNAL");
        this.client.pushUpdate("""
                {"@type":"updateAuthorizationState",
                 "authorization_state":{"@type":"authorizationStateReady"}}""");

        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).stage()).isEqualTo(LoginStage.READY);
        assertThat(notifications.get(0).tgUserId())
                .as("the identity lookup already succeeded and must not be undone by the "
                        + "chat-list warm-up failing afterward")
                .isEqualTo(777L);
    }

    @Test
    void notifiesTheListenerForNonReadyTransitionsTooIncludingLogout() {
        List<LoginState> notifications = new ArrayList<>();
        this.gateway.onStateChange(this.ref, notifications::add);

        this.client.pushUpdate("""
                {"@type":"updateAuthorizationState","authorization_state":{"@type":"authorizationStateClosed"}}""");

        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).stage()).isEqualTo(LoginStage.DISCONNECTED);
    }

    @Test
    void sendsADatabaseEncryptionKeyDerivedFromTheConfiguredSessionEncryptionKey() {
        // Security-audit finding F19: without this field, TDLib stored the local session and
        // chat-metadata database at database_directory completely unencrypted on disk.
        this.client.enqueue("{\"@type\":\"ok\"}");

        this.client.pushUpdate("""
                {"@type":"updateAuthorizationState",
                 "authorization_state":{"@type":"authorizationStateWaitTdlibParameters"}}""");

        Map<String, Object> sentParams = this.client.lastParamsFor("setTdlibParameters");
        assertThat(sentParams).isNotNull();
        Object key = sentParams.get("database_encryption_key");
        assertThat(key).as("must not be blank, null, or the raw configured secret verbatim").isNotNull();
        assertThat((String) key).isNotBlank()
                .isEqualTo(TdlibTelegramGateway
                        .deriveDatabaseEncryptionKey("dGVzdC1zZXNzaW9uLWVuY3J5cHRpb24ta2V5LTMyYnl0ZXM="));
    }

    @Test
    void derivingTheDatabaseEncryptionKeyIsDeterministicButDistinctPerConfiguredSecret() {
        String secretA = "dGVzdC1zZXNzaW9uLWVuY3J5cHRpb24ta2V5LTMyYnl0ZXM=";
        String secretB = "b3RoZXItc2Vzc2lvbi1lbmNyeXB0aW9uLWtleS0zMmJ5dGVz";

        String derivedFromKeyA = TdlibTelegramGateway.deriveDatabaseEncryptionKey(secretA);
        String derivedFromKeyAAgain = TdlibTelegramGateway.deriveDatabaseEncryptionKey(secretA);
        String derivedFromKeyB = TdlibTelegramGateway.deriveDatabaseEncryptionKey(secretB);

        assertThat(derivedFromKeyA).isEqualTo(derivedFromKeyAAgain).isNotEqualTo(derivedFromKeyB);
        // Must never just be the configured secret handed straight to TDLib.
        assertThat(derivedFromKeyA).isNotEqualTo(secretA);
    }

    @Test
    void mapsChatTypes() {
        assertThat(this.gateway.toRemoteChat(json("""
                {"id":-100123,"title":"Eng","type":{"@type":"chatTypeSupergroup","supergroup_id":9,
                 "is_channel":false}}""")).chatType()).isEqualTo("SUPERGROUP");
        assertThat(this.gateway.toRemoteChat(json("""
                {"id":-100123,"title":"News","type":{"@type":"chatTypeSupergroup","supergroup_id":9,
                 "is_channel":true}}""")).chatType()).isEqualTo("CHANNEL");
        assertThat(this.gateway.toRemoteChat(json("""
                {"id":5,"title":"DM","type":{"@type":"chatTypePrivate","user_id":5}}""")).chatType())
                .isEqualTo("PRIVATE");
    }

    /**
     * Real-account testing found the "refresh chat list" button crashing every single time
     * with a raw, uncaught 404 from loadChats. TDLib answers 404 ("Not Found") from loadChats
     * to mean the chat list is already fully loaded -- documented behaviour, not a failure --
     * but TdlibErrors.translate() has no specific mapping for code 404, so it falls through to
     * TelegramTransientException, not TelegramPermissionException, which is what
     * listAvailableChats() was catching. getChats/getChat must still run and return whatever is
     * already loaded rather than letting that exception propagate.
     */
    @Test
    void treatsLoadChatsAnsweringNotFoundAsTheChatListAlreadyBeingFullyLoaded() {
        this.client.enqueueError(404, "Not Found");
        this.client.enqueue("""
                {"@type":"chats","chat_ids":[-100123]}""");
        this.client.enqueue("""
                {"@type":"chat","id":-100123,"title":"Eng",
                 "type":{"@type":"chatTypeSupergroup","supergroup_id":9,"is_channel":false}}""");

        List<RemoteChat> chats = this.gateway.listAvailableChats(this.ref);

        assertThat(chats).extracting(RemoteChat::tgChatId).containsExactly(-100123L);
    }

    @Test
    void mapsATextMessageIncludingTheEditTimestamp() {
        RemoteMessage message = this.gateway.toRemoteMessage(json("""
                {"id":8192,"chat_id":-100123,"date":1780000000,"edit_date":1780000600,
                 "sender_id":{"@type":"messageSenderUser","user_id":777},
                 "reply_to":{"@type":"messageReplyToMessage","message_id":8191},
                 "content":{"@type":"messageText","text":{"@type":"formattedText","text":"hello"}}}"""));

        assertThat(message.messageId()).isEqualTo(8192L);
        assertThat(message.senderTgUserId()).isEqualTo(777L);
        assertThat(message.text()).isEqualTo("hello");
        assertThat(message.sentAt()).isEqualTo(Instant.ofEpochSecond(1780000000L));
        assertThat(message.editedAt()).isEqualTo(Instant.ofEpochSecond(1780000600L));
        assertThat(message.replyToMessageId()).isEqualTo(8191L);
    }

    @Test
    void leavesEditedAtNullWhenTdlibReportsZero() {
        assertThat(this.gateway.toRemoteMessage(json("""
                {"id":1,"date":1780000000,"edit_date":0,
                 "content":{"@type":"messageText","text":{"text":"x"}}}""")).editedAt()).isNull();
    }

    @Test
    void labelsMediaWithItsCaptionRatherThanContributingAnEmptyBody() {
        // Media is not downloaded, transcribed or OCR'd in this MVP -- but real-account testing
        // found that used to mean an empty body, indistinguishable from no message at all.
        assertThat(TdlibTelegramGateway.extractText(json("""
                {"@type":"messagePhoto","caption":{"text":"a diagram"}}"""))).isEqualTo("[Photo]: a diagram");
        assertThat(TdlibTelegramGateway.extractText(json("""
                {"@type":"messagePhoto","caption":{"text":""}}"""))).isEqualTo("[Photo]");
        assertThat(TdlibTelegramGateway.extractText(json("""
                {"@type":"messageVoiceNote"}"""))).isEqualTo("[Voice message]");
        assertThat(TdlibTelegramGateway.extractText(json("""
                {"@type":"messageDocument","document":{"file_name":"report.pdf"}}""")))
                .isEqualTo("[File: report.pdf]");
        assertThat(TdlibTelegramGateway.extractText(json("""
                {"@type":"messageDocument","document":{"file_name":""},"caption":{"text":"see attached"}}""")))
                .isEqualTo("[File]: see attached");
        assertThat(TdlibTelegramGateway.extractText(json("""
                {"@type":"messageSticker","sticker":{"emoji":"😀"}}"""))).isEqualTo("[Sticker 😀]");
        // A genuinely unhandled content type (e.g. a service message) still contributes nothing.
        assertThat(TdlibTelegramGateway.extractText(json("""
                {"@type":"messageChatChangeTitle","title":"New name"}"""))).isEmpty();
    }

    @Test
    void filtersFetchedMessagesToTheRequestedWindow() {
        this.client.enqueue("""
                {"@type":"messages","total_count":3,"messages":[
                  {"id":30,"date":1780002000,"content":{"@type":"messageText","text":{"text":"inside"}}},
                  {"id":20,"date":1700000000,"content":{"@type":"messageText","text":{"text":"too old"}}},
                  {"id":40,"date":1900000000,"content":{"@type":"messageText","text":{"text":"too new"}}}]}""");

        RemoteMessagePage page = this.gateway.fetchMessages(this.ref, -100123L,
                new MessageWindow(Instant.ofEpochSecond(1780000000L), Instant.ofEpochSecond(1780005000L),
                        null, null, 10));

        assertThat(page.messages()).extracting(RemoteMessage::text).containsExactly("inside");
    }

    @Test
    void honoursTheAfterMessageIdBoundForIncrementalCatchUp() {
        this.client.enqueue("""
                {"@type":"messages","messages":[
                  {"id":10,"date":1780002000,"content":{"@type":"messageText","text":{"text":"old"}}},
                  {"id":11,"date":1780002100,"content":{"@type":"messageText","text":{"text":"new"}}}]}""");

        RemoteMessagePage page = this.gateway.fetchMessages(this.ref, -100123L,
                MessageWindow.forward(Instant.ofEpochSecond(1780000000L),
                        Instant.ofEpochSecond(1780005000L), 10L, 10));

        assertThat(page.messages()).extracting(RemoteMessage::messageId).containsExactly(11L);
    }

    @Test
    void walksMultipleRawPagesWithinOneCallToReachMessagesPastANewerThanWindowBacklog() {
        // Security-audit finding F12 regression: 14 "noise" messages newer than window.to()
        // sit ahead of 3 in-window messages, which sit ahead of messages older than
        // window.from(). The old implementation only ever inspected the single newest raw
        // page (ids 100-96, all noise) and gave up there, silently losing the in-window
        // messages entirely -- exactly "yangi xabarlar to'planib, eski cursor sakrab o'tib
        // ketishi" from the audit.
        this.client.enqueue(historyPage(new long[] {100, 99, 98, 97, 96}, 3000L));
        this.client.enqueue(historyPage(new long[] {95, 94, 93, 92, 91}, 3000L));
        this.client.enqueue(historyPage(new long[] {90, 89, 88, 87, 86},
                new long[] {3000L, 3000L, 1500L, 1500L, 1500L}));
        this.client.enqueue(historyPage(new long[] {85, 84, 83, 82, 81}, 500L));

        RemoteMessagePage page = this.gateway.fetchMessages(this.ref, -100123L,
                MessageWindow.forward(Instant.ofEpochSecond(1000L), Instant.ofEpochSecond(2000L), null, 5));

        assertThat(page.messages()).extracting(RemoteMessage::messageId).containsExactly(86L, 87L, 88L);
        assertThat(page.hasMore())
                .as("traversal reached messages older than window.from(): genuinely done")
                .isFalse();
        assertThat(page.resumeCursor()).isNull();
    }

    @Test
    void aResumeCursorLetsASecondCallContinueExactlyWhereTheInternalPageCapStopped() {
        // Forces MAX_HISTORY_RAW_PAGES_PER_CALL worth of pure noise (never reaching the
        // in-window content, never reaching a boundary) so the first call must stop on the
        // cap alone and hand back a resume point rather than silently reporting "nothing here".
        int pageSize = 5;
        long id = 1000L;
        for (int page = 0; page < TdlibTelegramGateway.MAX_HISTORY_RAW_PAGES_PER_CALL; page++) {
            long[] ids = new long[pageSize];
            for (int i = 0; i < pageSize; i++) {
                ids[i] = id--;
            }
            this.client.enqueue(historyPage(ids, 3000L));
        }

        RemoteMessagePage firstCall = this.gateway.fetchMessages(this.ref, -100123L,
                MessageWindow.forward(Instant.ofEpochSecond(1000L), Instant.ofEpochSecond(2000L), null, pageSize));

        assertThat(firstCall.messages()).isEmpty();
        assertThat(firstCall.hasMore())
                .as("the page cap was hit before reaching the in-window content or a real boundary")
                .isTrue();
        assertThat(firstCall.resumeCursor()).isNotNull();

        long resumeFrom = firstCall.resumeCursor();
        long[] nextIds = {resumeFrom - 3, resumeFrom - 2, resumeFrom - 1};
        this.client.enqueue(historyPage(nextIds, 1500L));

        RemoteMessagePage secondCall = this.gateway.fetchMessages(this.ref, -100123L,
                MessageWindow.forward(Instant.ofEpochSecond(1000L), Instant.ofEpochSecond(2000L), null,
                        resumeFrom, pageSize));

        assertThat(secondCall.messages()).extracting(RemoteMessage::messageId)
                .containsExactly(nextIds[0], nextIds[1], nextIds[2]);
        assertThat(secondCall.hasMore()).isFalse();
    }

    private String historyPage(long[] ids, long uniformDateEpochSeconds) {
        long[] dates = new long[ids.length];
        java.util.Arrays.fill(dates, uniformDateEpochSeconds);
        return historyPage(ids, dates);
    }

    private String historyPage(long[] ids, long[] dateEpochSeconds) {
        StringBuilder messages = new StringBuilder();
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                messages.append(',');
            }
            messages.append("{\"id\":").append(ids[i])
                    .append(",\"date\":").append(dateEpochSeconds[i])
                    .append(",\"content\":{\"@type\":\"messageText\",\"text\":{\"text\":\"m")
                    .append(ids[i]).append("\"}}}");
        }
        return "{\"@type\":\"messages\",\"messages\":[" + messages + "]}";
    }

    @Test
    void returnsOnlyRealUsersAsParticipantsAndFlagsBots() {
        this.client.enqueue("""
                {"@type":"chat","id":-100123,"title":"Eng",
                 "type":{"@type":"chatTypeSupergroup","supergroup_id":55,"is_channel":false}}""");
        this.client.enqueue("""
                {"@type":"chatMembers","total_count":2,"members":[
                  {"member_id":{"@type":"messageSenderUser","user_id":1}},
                  {"member_id":{"@type":"messageSenderChat","chat_id":-9}},
                  {"member_id":{"@type":"messageSenderUser","user_id":2}}]}""");
        this.client.enqueue("""
                {"@type":"user","id":1,"first_name":"Ada","last_name":"Lovelace",
                 "type":{"@type":"userTypeRegular"}}""");
        this.client.enqueue("""
                {"@type":"user","id":2,"first_name":"Helper","last_name":"",
                 "type":{"@type":"userTypeBot"}}""");

        RemoteMembership membership = this.gateway.listParticipants(this.ref, -100123L);

        assertThat(membership.complete()).isTrue();
        assertThat(membership.participants()).containsExactly(
                new RemoteParticipant(1L, "Ada Lovelace", false),
                new RemoteParticipant(2L, "Helper", true));
    }

    /**
     * Security-audit finding F04: listParticipants used to fetch a single fixed 200-member
     * page, so any supergroup larger than that silently reported only its first 200 members.
     * This drives a 205-member chat across two pages and checks every member came back.
     */
    @Test
    void pagesThroughSupergroupsLargerThanOneMemberPage() {
        this.client.enqueue("""
                {"@type":"chat","id":-100999,"title":"Big",
                 "type":{"@type":"chatTypeSupergroup","supergroup_id":77,"is_channel":false}}""");
        this.client.enqueue(membersPage(1, 200));
        for (long id = 1; id <= 200; id++) {
            this.client.enqueue(regularUser(id));
        }
        this.client.enqueue(membersPage(201, 5));
        for (long id = 201; id <= 205; id++) {
            this.client.enqueue(regularUser(id));
        }

        RemoteMembership membership = this.gateway.listParticipants(this.ref, -100999L);

        assertThat(membership.complete()).isTrue();
        List<RemoteParticipant> participants = membership.participants();
        assertThat(participants).hasSize(205);
        assertThat(participants).extracting(RemoteParticipant::tgUserId)
                .containsExactlyInAnyOrderElementsOf(
                        java.util.stream.LongStream.rangeClosed(1, 205).boxed().toList());
    }

    /**
     * Security-audit finding SEC-02: reaching the safety cap must report the result as
     * incomplete, not silently truncate it, so callers never treat "as far as we were willing
     * to page through" as "confirmed, this is everyone" -- a distinction {@code
     * pagesThroughSupergroupsLargerThanOneMemberPage} above does not exercise, since 205
     * members never comes close to MAX_MEMBERS.
     */
    @Test
    void reportsIncompleteWhenTheMemberSafetyCapIsReachedRatherThanTheSourceRunningOut() {
        this.client.enqueue("""
                {"@type":"chat","id":-100888,"title":"Huge",
                 "type":{"@type":"chatTypeSupergroup","supergroup_id":88,"is_channel":false}}""");
        // Each page is a full 200-member page (MEMBER_PAGE_SIZE), so the loop keeps asking for
        // more pages until MAX_MEMBERS itself stops it, never seeing a short, source-exhausted
        // page that would tell it it reached the real end.
        int pageSize = 200;
        int pages = TdlibTelegramGateway.MAX_MEMBERS / pageSize;
        long nextId = 1;
        for (int page = 0; page < pages; page++) {
            this.client.enqueue(membersPage(nextId, pageSize));
            for (int i = 0; i < pageSize; i++) {
                this.client.enqueue(regularUser(nextId + i));
            }
            nextId += pageSize;
        }

        RemoteMembership membership = this.gateway.listParticipants(this.ref, -100888L);

        assertThat(membership.complete())
                .as("hitting the safety cap must never be reported the same as the source genuinely "
                        + "running out of members")
                .isFalse();
        assertThat(membership.participants()).hasSize(TdlibTelegramGateway.MAX_MEMBERS);
    }

    private String membersPage(long startId, int count) {
        StringBuilder members = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                members.append(',');
            }
            members.append("{\"member_id\":{\"@type\":\"messageSenderUser\",\"user_id\":")
                    .append(startId + i).append("}}");
        }
        return "{\"@type\":\"chatMembers\",\"members\":[" + members + "]}";
    }

    private String regularUser(long id) {
        return "{\"@type\":\"user\",\"id\":" + id + ",\"first_name\":\"U" + id
                + "\",\"last_name\":\"\",\"type\":{\"@type\":\"userTypeRegular\"}}";
    }

    /**
     * Real-account testing found a private (1-on-1) chat could never collect consent: its
     * counterpart was treated as "no member list available" just like a basic group, leaving
     * the participant table permanently empty with no row to ever record their consent
     * against. A private chat's counterpart needs no separate members call -- it is named
     * directly in the chat's own {@code type}.
     */
    @Test
    void returnsThePrivateChatCounterpartAsItsOnlyParticipant() {
        this.client.enqueue("""
                {"@type":"chat","id":5,"title":"DM","type":{"@type":"chatTypePrivate","user_id":42}}""");
        this.client.enqueue("""
                {"@type":"user","id":42,"first_name":"Farrukh","last_name":"Gulomov",
                 "type":{"@type":"userTypeRegular"}}""");

        RemoteMembership membership = this.gateway.listParticipants(this.ref, 5L);
        assertThat(membership.complete()).isTrue();
        assertThat(membership.participants()).containsExactly(new RemoteParticipant(42L, "Farrukh Gulomov", false));
    }

    @Test
    void returnsNoParticipantsForChatTypesThatDoNotExposeAMemberList() {
        this.client.enqueue("""
                {"@type":"chat","id":9,"title":"Secret chat",
                 "type":{"@type":"chatTypeSecret","secret_chat_id":9,"user_id":42}}""");

        RemoteMembership membership = this.gateway.listParticipants(this.ref, 9L);
        assertThat(membership.participants()).isEmpty();
        assertThat(membership.complete())
                .as("confirmed empty is not the same as unverifiable, and must not be reported as such")
                .isTrue();
    }

    /**
     * Real-account testing found a plain (non-super) group could never collect consent either,
     * for the same reason as the private-chat case above: basic groups fell into the same "no
     * member list available" catch-all as truly unsupported chat types, leaving the participant
     * table permanently empty. Unlike a supergroup, a basic group has no separate paginated
     * members call and no size beyond Telegram's own 200-member cap for this chat type --
     * getBasicGroupFullInfo returns the whole roster in one shot.
     */
    @Test
    void returnsBasicGroupMembersViaGetBasicGroupFullInfo() {
        this.client.enqueue("""
                {"@type":"chat","id":9,"title":"Small group",
                 "type":{"@type":"chatTypeBasicGroup","basic_group_id":9}}""");
        this.client.enqueue("""
                {"@type":"basicGroupFullInfo","members":[
                  {"member_id":{"@type":"messageSenderUser","user_id":1}},
                  {"member_id":{"@type":"messageSenderChat","chat_id":-9}},
                  {"member_id":{"@type":"messageSenderUser","user_id":2}}]}""");
        this.client.enqueue("""
                {"@type":"user","id":1,"first_name":"Ada","last_name":"Lovelace",
                 "type":{"@type":"userTypeRegular"}}""");
        this.client.enqueue("""
                {"@type":"user","id":2,"first_name":"Helper","last_name":"",
                 "type":{"@type":"userTypeBot"}}""");

        RemoteMembership membership = this.gateway.listParticipants(this.ref, 9L);

        assertThat(membership.complete()).isTrue();
        assertThat(membership.participants()).containsExactly(
                new RemoteParticipant(1L, "Ada Lovelace", false),
                new RemoteParticipant(2L, "Helper", true));
    }

    /**
     * Security-audit finding SEC-02: getBasicGroupFullInfo refusing to answer at all is a
     * source that could not confirm the membership, not a verified empty roster -- reported as
     * incomplete so it is never mistaken for "this group genuinely has no members".
     */
    @Test
    void reportsIncompleteRatherThanThrowingWhenABasicGroupsMemberListIsInaccessible() {
        this.client.enqueue("""
                {"@type":"chat","id":9,"title":"Restricted small group",
                 "type":{"@type":"chatTypeBasicGroup","basic_group_id":9}}""");
        this.client.enqueueError(400, "Not enough rights");

        RemoteMembership membership = this.gateway.listParticipants(this.ref, 9L);
        assertThat(membership.complete()).isFalse();
        assertThat(membership.participants()).isEmpty();
    }

    /**
     * Real-account testing found Telegram routinely refuses getSupergroupMembers ("Member list
     * is inaccessible", code 400) for a supergroup the connected account cannot see the
     * membership of -- a normal, common situation, not a bug. Before this fix the exception
     * propagated out of listParticipants() uncaught, crashing the entire "select this chat"
     * action (ChatActivationService.propose()) instead of leaving participants empty, which
     * already surfaces as ConsentCoverage.noParticipantsKnown() and correctly blocks
     * activation. Security-audit finding SEC-02: reported as incomplete rather than complete-
     * empty, for the same reason as the basic-group case above.
     */
    @Test
    void reportsIncompleteRatherThanThrowingWhenTheMemberListIsInaccessible() {
        this.client.enqueue("""
                {"@type":"chat","id":-100777,"title":"Restricted",
                 "type":{"@type":"chatTypeSupergroup","supergroup_id":88,"is_channel":false}}""");
        this.client.enqueueError(400, "Member list is inaccessible");

        RemoteMembership membership = this.gateway.listParticipants(this.ref, -100777L);
        assertThat(membership.complete()).isFalse();
        assertThat(membership.participants()).isEmpty();
    }

    @Test
    void returnsAMessageLinkOnlyWhenTelegramSaysItIsPublic() {
        RemoteChat chat = new RemoteChat(-100123L, "Eng", "SUPERGROUP", "eng", null);

        this.client.enqueue("""
                {"@type":"messageLink","link":"https://t.me/eng/42","is_public":true}""");
        assertThat(this.gateway.messageLink(chat, 42L)).contains("https://t.me/eng/42");

        // A private link resolves for nobody else, so we hand out nothing rather than a dud.
        this.client.enqueue("""
                {"@type":"messageLink","link":"https://t.me/c/123/42","is_public":false}""");
        assertThat(this.gateway.messageLink(chat, 42L)).isEmpty();
    }

    @Test
    void neverFabricatesALinkWhenTelegramRefuses() {
        this.client.enqueueError(403, "CHANNEL_PRIVATE");

        assertThat(this.gateway.messageLink(new RemoteChat(-100123L, "x", "SUPERGROUP", null, null), 1L))
                .isEqualTo(Optional.empty());
    }

    @Test
    void skipsTheCallEntirelyForChatTypesTelegramDocumentsAsNeverSupportingLinks() {
        // Real-account testing found getMessageLink answering "Message links are available only
        // for messages in supergroups and channel chats" for every message in a private chat or
        // a basic group -- a doomed call repeated once per message. No response is enqueued
        // here; ScriptedTdlibClient would throw if the gateway called TDLib at all.
        assertThat(this.gateway.messageLink(new RemoteChat(5L, "DM", "PRIVATE", null, null), 1L))
                .isEmpty();
        assertThat(this.gateway.messageLink(new RemoteChat(-1L, "Basic group", "GROUP", null, null), 1L))
                .isEmpty();
    }

    // --- SEC-01: shared session ownership ----------------------------------------------

    /**
     * A second application user's own, never-claimed {@link ConnectionRef} -- exactly what
     * {@code TelegramConnectionService} builds for any actor who has merely called {@code
     * getOrCreate}, e.g. by visiting {@code /connect}, whether or not they ever started a real
     * login. Every read/write method below must reject it without making any native call at
     * all: {@link ScriptedTdlibClient#request} throws its own {@code AssertionError} if a
     * request is attempted with nothing enqueued for it, so a passing test here proves no TDLib
     * call was reachable, not just that the return value happened to look denied.
     */
    private final ConnectionRef foreignRef = new ConnectionRef(UUID.randomUUID(), UUID.randomUUID());

    @Test
    void rejectsEveryOperationFromAConnectionThatNeverClaimedOwnership() {
        assertThatThrownBy(() -> this.gateway.submitCode(this.foreignRef, "12345"))
                .isInstanceOf(TelegramAuthException.class);
        assertThatThrownBy(() -> this.gateway.submitPassword(this.foreignRef, "hunter2"))
                .isInstanceOf(TelegramAuthException.class);
        assertThatThrownBy(() -> this.gateway.currentState(this.foreignRef))
                .isInstanceOf(TelegramAuthException.class);
        assertThatThrownBy(() -> this.gateway.disconnect(this.foreignRef))
                .isInstanceOf(TelegramAuthException.class);
        assertThatThrownBy(() -> this.gateway.listAvailableChats(this.foreignRef))
                .isInstanceOf(TelegramAuthException.class);
        assertThatThrownBy(() -> this.gateway.listParticipants(this.foreignRef, -100123L))
                .isInstanceOf(TelegramAuthException.class);
        assertThatThrownBy(() -> this.gateway.fetchMessages(this.foreignRef,
                -100123L, new MessageWindow(Instant.EPOCH, Instant.now(), null, null, 10)))
                .isInstanceOf(TelegramAuthException.class);
        assertThatThrownBy(() -> this.gateway.fetchRevisions(this.foreignRef, -100123L, Instant.EPOCH, 10))
                .isInstanceOf(TelegramAuthException.class);
    }

    @Test
    void aSecondLoginAttemptBySomeoneElseTakesOverAndTheFirstOwnersRefStopsWorking() {
        // Mirrors what TelegramConnectionService.beginLogin does for a brand new owner: bind
        // the listener, then start the login. Ownership moves to refB even though refA was
        // claimed first -- the atomic single-owner decision belongs to
        // TelegramConnectionService (security-audit finding SEC-01's other half); this class
        // only enforces whichever ref last claimed it.
        ConnectionRef refB = new ConnectionRef(UUID.randomUUID(), UUID.randomUUID());
        this.client.enqueue("{\"@type\":\"ok\"}");
        this.gateway.onStateChange(refB, state -> { });
        this.gateway.beginLogin(refB, io.tgmcp.telegram.gateway.LoginMethod.QR_CODE, null);

        assertThatThrownBy(() -> this.gateway.currentState(this.ref))
                .as("the previous owner's ref must stop working the moment someone else claims ownership")
                .isInstanceOf(TelegramAuthException.class);

        // The new owner's own ref keeps working.
        assertThat(this.gateway.currentState(refB)).isNotNull();
    }

    @Test
    void disconnectReleasesOwnershipSoNoStaleRefIsAccidentallyHonoured() {
        this.client.enqueue("{\"@type\":\"ok\"}");
        this.gateway.disconnect(this.ref);

        assertThatThrownBy(() -> this.gateway.currentState(this.ref))
                .as("even the owner that just disconnected must not keep working against a cleared claim")
                .isInstanceOf(TelegramAuthException.class);
    }

    // --- F08: edit/delete revision pipeline ----------------------------------------------

    @Test
    void anEditedMessageIsBufferedAndReturnedByFetchRevisions() {
        this.client.enqueue("""
                {"@type":"message","id":42,"date":1700000000,"edit_date":1700000100,
                 "sender_id":{"@type":"messageSenderUser","user_id":7},
                 "content":{"@type":"messageText","text":{"text":"edited body"}}}""");

        this.client.pushUpdate("""
                {"@type":"updateMessageContent","chat_id":-100123,"message_id":42,
                 "new_content":{"@type":"messageText","text":{"text":"edited body"}}}""");

        List<RemoteMessage> revisions = this.gateway.fetchRevisions(this.ref, -100123L, Instant.EPOCH, 50);

        assertThat(revisions).hasSize(1);
        RemoteMessage revision = revisions.get(0);
        assertThat(revision.messageId()).isEqualTo(42L);
        assertThat(revision.deleted()).isFalse();
        assertThat(revision.text()).isEqualTo("edited body");
        assertThat(revision.senderTgUserId()).isEqualTo(7L);
        assertThat(revision.editedAt()).isEqualTo(Instant.ofEpochSecond(1700000100));
    }

    @Test
    void deletedMessagesAreBufferedAndReturnedByFetchRevisions() {
        this.client.pushUpdate("""
                {"@type":"updateDeleteMessages","chat_id":-100123,"message_ids":[10,11],
                 "is_permanent":true,"from_cache":false}""");

        List<RemoteMessage> revisions = this.gateway.fetchRevisions(this.ref, -100123L, Instant.EPOCH, 50);

        assertThat(revisions).hasSize(2);
        assertThat(revisions).allSatisfy(r -> assertThat(r.deleted()).isTrue());
        assertThat(revisions).extracting(RemoteMessage::messageId).containsExactlyInAnyOrder(10L, 11L);
    }

    @Test
    void fetchRevisionsDrainsOnlyUpToTheRequestedLimitLeavingTheRestQueued() {
        this.client.pushUpdate("""
                {"@type":"updateDeleteMessages","chat_id":-100123,"message_ids":[1,2,3],
                 "is_permanent":true,"from_cache":false}""");

        List<RemoteMessage> firstBatch = this.gateway.fetchRevisions(this.ref, -100123L, Instant.EPOCH, 2);
        assertThat(firstBatch).extracting(RemoteMessage::messageId).containsExactly(1L, 2L);

        List<RemoteMessage> secondBatch = this.gateway.fetchRevisions(this.ref, -100123L, Instant.EPOCH, 2);
        assertThat(secondBatch).extracting(RemoteMessage::messageId).containsExactly(3L);
    }

    @Test
    void fetchRevisionsReturnsEmptyForAChatWithNoBufferedActivity() {
        assertThat(this.gateway.fetchRevisions(this.ref, -999L, Instant.EPOCH, 10)).isEmpty();
    }

    /**
     * The follow-up getMessage() call after an edit push can itself fail (e.g. the message was
     * deleted moments later); this must be swallowed on the update-dispatch path, not thrown,
     * since nothing calls fetchRevisions() from there to catch it.
     */
    @Test
    void aFailedFollowUpFetchAfterAnEditIsSwallowedRatherThanPropagated() {
        this.client.enqueueError(400, "MESSAGE_ID_INVALID");

        this.client.pushUpdate("""
                {"@type":"updateMessageContent","chat_id":-100123,"message_id":999,
                 "new_content":{"@type":"messageText","text":{"text":"x"}}}""");

        assertThat(this.gateway.fetchRevisions(this.ref, -100123L, Instant.EPOCH, 10)).isEmpty();
    }

    @Test
    void disconnectClearsAnyBufferedRevisionsSoALaterLoginDoesNotInheritThem() {
        this.client.pushUpdate("""
                {"@type":"updateDeleteMessages","chat_id":-100123,"message_ids":[1],
                 "is_permanent":true,"from_cache":false}""");
        this.client.enqueue("{\"@type\":\"ok\"}");
        this.gateway.disconnect(this.ref);

        ConnectionRef newOwner = new ConnectionRef(UUID.randomUUID(), UUID.randomUUID());
        this.gateway.onStateChange(newOwner, state -> { });

        assertThat(this.gateway.fetchRevisions(newOwner, -100123L, Instant.EPOCH, 10)).isEmpty();
    }

    /** Replays canned TDLib responses in order; records what was sent. */
    private static final class ScriptedTdlibClient implements TdlibClient {

        private final ObjectMapper mapper;
        private final Deque<String> responses = new ArrayDeque<>();
        private final List<String> sentMethods = new ArrayList<>();
        private final java.util.Map<String, Map<String, Object>> lastParamsByMethod = new java.util.HashMap<>();
        private Consumer<JsonNode> updateListener;

        private ScriptedTdlibClient(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        void enqueue(String json) {
            this.responses.addLast(json);
        }

        /** Simulates TDLib delivering a push update (security-audit finding F13 tests). */
        void pushUpdate(String json) {
            this.updateListener.accept(this.mapper.readTree(json));
        }

        void enqueueError(int code, String message) {
            this.responses.addLast("{\"@type\":\"error\",\"code\":" + code
                    + ",\"message\":\"" + message + "\"}");
        }

        Map<String, Object> lastParamsFor(String method) {
            return this.lastParamsByMethod.get(method);
        }

        @Override
        public JsonNode request(String method, Map<String, Object> params) {
            this.sentMethods.add(method);
            this.lastParamsByMethod.put(method, params);
            String response = this.responses.pollFirst();
            if (response == null) {
                throw new AssertionError("No scripted TDLib response for '" + method + "'");
            }
            JsonNode node = this.mapper.readTree(response);
            if (TdlibErrors.isError(node)) {
                throw TdlibErrors.translate(node);
            }
            return node;
        }

        @Override
        public void onUpdate(Consumer<JsonNode> listener) {
            this.updateListener = listener;
        }

        @Override
        public void close() {
        }
    }
}
