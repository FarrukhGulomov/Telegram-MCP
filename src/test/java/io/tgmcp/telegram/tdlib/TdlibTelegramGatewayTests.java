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
import io.tgmcp.telegram.gateway.MessageWindow;
import io.tgmcp.telegram.gateway.RemoteChat;
import io.tgmcp.telegram.gateway.RemoteMessage;
import io.tgmcp.telegram.gateway.RemoteMessagePage;
import io.tgmcp.telegram.gateway.RemoteParticipant;
import io.tgmcp.telegram.gateway.TelegramAdapterKind;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

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
            new AppProperties.Telegram(TelegramAdapterKind.TDLIB, "12345", "hash", "./tdlib", true);
    private final TdlibTelegramGateway gateway = new TdlibTelegramGateway(this.client, this.config);

    private final ConnectionRef ref = new ConnectionRef(UUID.randomUUID(), UUID.randomUUID());

    private JsonNode json(String raw) {
        return this.mapper.readTree(raw);
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

        List<RemoteParticipant> participants = this.gateway.listParticipants(this.ref, -100123L);

        assertThat(participants).containsExactly(
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

        List<RemoteParticipant> participants = this.gateway.listParticipants(this.ref, -100999L);

        assertThat(participants).hasSize(205);
        assertThat(participants).extracting(RemoteParticipant::tgUserId)
                .containsExactlyInAnyOrderElementsOf(
                        java.util.stream.LongStream.rangeClosed(1, 205).boxed().toList());
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

        assertThat(this.gateway.listParticipants(this.ref, 5L)).isEmpty();
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

    /** Replays canned TDLib responses in order; records what was sent. */
    private static final class ScriptedTdlibClient implements TdlibClient {

        private final ObjectMapper mapper;
        private final Deque<String> responses = new ArrayDeque<>();
        private final List<String> sentMethods = new ArrayList<>();

        private ScriptedTdlibClient(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        void enqueue(String json) {
            this.responses.addLast(json);
        }

        void enqueueError(int code, String message) {
            this.responses.addLast("{\"@type\":\"error\",\"code\":" + code
                    + ",\"message\":\"" + message + "\"}");
        }

        @Override
        public JsonNode request(String method, Map<String, Object> params) {
            this.sentMethods.add(method);
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
        }

        @Override
        public void close() {
        }
    }
}
