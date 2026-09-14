package io.tgmcp.telegram.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
import io.tgmcp.telegram.gateway.TelegramGateway;
import io.tgmcp.telegram.gateway.TelegramGatewayException;
import io.tgmcp.telegram.gateway.TelegramPermissionException;

/**
 * In-memory Telegram adapter backed by synthetic conversations.
 *
 * <p>This is the default adapter so that the whole product — onboarding, consent,
 * activation, sync, MCP, revocation — is exercisable with no Telegram credentials at all.
 * It also exposes deliberate test seams ({@link #failNextCall}, {@link #editMessage},
 * {@link #deleteMessage}, {@link #appendMessage}) so that failure and mutation paths can
 * be driven from tests rather than simulated.
 */
public class MockTelegramGateway implements TelegramGateway {

    private final Clock clock;
    private final Map<Long, SyntheticChat> chats;
    private final Map<java.util.UUID, LoginState> sessions = new ConcurrentHashMap<>();
    private final Deque<TelegramGatewayException> injectedFailures = new ArrayDeque<>();
    private final Map<Long, Instant> revisionMarks = new ConcurrentHashMap<>();
    private final AtomicLong callCount = new AtomicLong();

    public MockTelegramGateway(Clock clock) {
        this.clock = clock;
        this.chats = new ConcurrentHashMap<>(SyntheticDataset.build(clock.instant()));
    }

    @Override
    public TelegramAdapterKind kind() {
        return TelegramAdapterKind.MOCK;
    }

    // --- test / demo seams ------------------------------------------------------------

    /**
     * Rebuilds the synthetic dataset from scratch. The adapter is a singleton, so tests that
     * edit or delete synthetic messages must reset it or they leak into one another.
     */
    public void reset() {
        this.chats.clear();
        this.chats.putAll(SyntheticDataset.build(this.clock.instant()));
        this.sessions.clear();
        this.injectedFailures.clear();
        this.revisionMarks.clear();
        this.callCount.set(0);
    }

    /** Makes the next read call throw. Used to exercise flood-wait and backoff handling. */
    public void failNextCall(TelegramGatewayException failure) {
        this.injectedFailures.addLast(failure);
    }

    public long callCount() {
        return this.callCount.get();
    }

    public void editMessage(long tgChatId, long messageId, String newText) {
        mutate(tgChatId, messageId, existing -> new RemoteMessage(existing.messageId(), existing.senderTgUserId(),
                existing.senderDisplayName(), existing.sentAt(), this.clock.instant(), false, newText,
                existing.replyToMessageId()));
    }

    public void deleteMessage(long tgChatId, long messageId) {
        mutate(tgChatId, messageId, existing -> new RemoteMessage(existing.messageId(), existing.senderTgUserId(),
                existing.senderDisplayName(), existing.sentAt(), existing.editedAt(), true, "",
                existing.replyToMessageId()));
    }

    public RemoteMessage appendMessage(long tgChatId, long senderTgUserId, String senderName, String text) {
        SyntheticChat chat = requireChat(tgChatId);
        long nextId = chat.messages().stream().mapToLong(RemoteMessage::messageId).max().orElse(0L) + 1;
        RemoteMessage message = new RemoteMessage(nextId, senderTgUserId, senderName, this.clock.instant(),
                null, false, text, null);
        chat.messages().add(message);
        return message;
    }

    public void addParticipant(long tgChatId, RemoteParticipant participant) {
        requireChat(tgChatId).participants().add(participant);
    }

    public void removeParticipant(long tgChatId, long tgUserId) {
        requireChat(tgChatId).participants().removeIf(p -> p.tgUserId() == tgUserId);
    }

    private void mutate(long tgChatId, long messageId, java.util.function.UnaryOperator<RemoteMessage> op) {
        SyntheticChat chat = requireChat(tgChatId);
        List<RemoteMessage> messages = chat.messages();
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).messageId() == messageId) {
                messages.set(i, op.apply(messages.get(i)));
                this.revisionMarks.put(tgChatId, this.clock.instant());
                return;
            }
        }
        throw new IllegalArgumentException("No synthetic message " + messageId + " in chat " + tgChatId);
    }

    // --- session lifecycle ------------------------------------------------------------

    @Override
    public LoginState beginLogin(ConnectionRef ref, LoginMethod method, String phoneNumber) {
        LoginState state = (method == LoginMethod.QR_CODE)
                ? new LoginState(LoginStage.WAIT_QR_SCAN, "tg://login?token=SYNTHETIC-DEMO-TOKEN", null, null,
                        "login.mock.qrLogin")
                : LoginState.of(LoginStage.WAIT_CODE, "login.mock.waitCode");
        this.sessions.put(ref.connectionId(), state);
        return state;
    }

    @Override
    public LoginState submitCode(ConnectionRef ref, String code) {
        // The mock always asks for a 2FA password so that the two-step path is exercised.
        LoginState state = LoginState.of(LoginStage.WAIT_PASSWORD, "login.mock.waitPassword");
        this.sessions.put(ref.connectionId(), state);
        return state;
    }

    @Override
    public LoginState submitPassword(ConnectionRef ref, String password) {
        LoginState state = new LoginState(LoginStage.READY, null, 400001L, "synthetic_demo_owner",
                "login.mock.ready");
        this.sessions.put(ref.connectionId(), state);
        return state;
    }

    @Override
    public LoginState currentState(ConnectionRef ref) {
        return this.sessions.getOrDefault(ref.connectionId(), LoginState.of(LoginStage.DISCONNECTED, null));
    }

    @Override
    public void disconnect(ConnectionRef ref) {
        this.sessions.remove(ref.connectionId());
    }

    // --- read-only data ---------------------------------------------------------------

    @Override
    public List<RemoteChat> listAvailableChats(ConnectionRef ref) {
        guard();
        return this.chats.values().stream().map(SyntheticChat::descriptor).toList();
    }

    @Override
    public List<RemoteParticipant> listParticipants(ConnectionRef ref, long tgChatId) {
        guard();
        return List.copyOf(requireChat(tgChatId).participants());
    }

    @Override
    public RemoteMessagePage fetchMessages(ConnectionRef ref, long tgChatId, MessageWindow window) {
        guard();
        List<RemoteMessage> all = requireChat(tgChatId).messages().stream()
                .filter(m -> !m.deleted())
                .filter(m -> !m.sentAt().isBefore(window.from()) && !m.sentAt().isAfter(window.to()))
                .filter(m -> window.afterMessageId() == null || m.messageId() > window.afterMessageId())
                .filter(m -> window.beforeMessageId() == null || m.messageId() < window.beforeMessageId())
                .sorted(Comparator.comparingLong(RemoteMessage::messageId))
                .toList();

        // Backwards backfill takes the newest slice of the remaining window.
        List<RemoteMessage> selected;
        boolean hasMore;
        if (window.beforeMessageId() != null && all.size() > window.limit()) {
            selected = new ArrayList<>(all.subList(all.size() - window.limit(), all.size()));
            hasMore = true;
        }
        else if (all.size() > window.limit()) {
            selected = new ArrayList<>(all.subList(0, window.limit()));
            hasMore = true;
        }
        else {
            selected = new ArrayList<>(all);
            hasMore = false;
        }
        return new RemoteMessagePage(List.copyOf(selected), hasMore);
    }

    @Override
    public List<RemoteMessage> fetchRevisions(ConnectionRef ref, long tgChatId, Instant changedSince, int limit) {
        guard();
        Instant mark = this.revisionMarks.get(tgChatId);
        if (mark == null || mark.isBefore(changedSince)) {
            return List.of();
        }
        return requireChat(tgChatId).messages().stream()
                .filter(m -> m.deleted() || (m.editedAt() != null && !m.editedAt().isBefore(changedSince)))
                .limit(limit)
                .toList();
    }

    @Override
    public Optional<String> messageLink(RemoteChat chat, long tgMessageId) {
        // Only public chats have a stable https link. We never fabricate one for private chats.
        if (chat.username() == null || chat.username().isBlank()) {
            return Optional.empty();
        }
        return Optional.of("https://t.me/" + chat.username() + "/" + tgMessageId);
    }

    private void guard() {
        this.callCount.incrementAndGet();
        TelegramGatewayException injected = this.injectedFailures.pollFirst();
        if (injected != null) {
            throw injected;
        }
    }

    private SyntheticChat requireChat(long tgChatId) {
        SyntheticChat chat = this.chats.get(tgChatId);
        if (chat == null) {
            throw new TelegramPermissionException("Chat is not visible to the connected account");
        }
        return chat;
    }

    /** Convenience for demos: how far back the synthetic data reaches. */
    public Instant earliestSyntheticMessage() {
        return this.chats.values().stream()
                .flatMap(c -> c.messages().stream())
                .map(RemoteMessage::sentAt)
                .min(Instant::compareTo)
                .orElse(this.clock.instant().minus(Duration.ofDays(30)));
    }
}
