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
    void takesTheCaptionForMediaAndNothingForUnsupportedContent() {
        assertThat(TdlibTelegramGateway.extractText(json("""
                {"@type":"messagePhoto","caption":{"text":"a diagram"}}"""))).isEqualTo("a diagram");
        // Media is not downloaded, transcribed or OCR'd in this MVP.
        assertThat(TdlibTelegramGateway.extractText(json("""
                {"@type":"messageVoiceNote"}"""))).isEmpty();
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

    @Test
    void returnsNoParticipantsForChatTypesThatDoNotExposeAMemberList() {
        this.client.enqueue("""
                {"@type":"chat","id":5,"title":"DM","type":{"@type":"chatTypePrivate","user_id":5}}""");

        RemoteMembership membership = this.gateway.listParticipants(this.ref, 5L);
        assertThat(membership.participants()).isEmpty();
        assertThat(membership.complete())
                .as("confirmed empty is not the same as unverifiable, and must not be reported as such")
                .isTrue();
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

        assertThat(this.gateway.messageLink(new RemoteChat(-1L, "x", "GROUP", null, null), 1L))
                .isEqualTo(Optional.empty());
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
