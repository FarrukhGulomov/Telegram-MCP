package io.tgmcp.telegram;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.tgmcp.audit.AuditAction;
import io.tgmcp.audit.AuditService;
import io.tgmcp.chat.Chat;
import io.tgmcp.chat.ChatRepository;
import io.tgmcp.common.Ids;
import io.tgmcp.tenancy.AuthenticatedActor;
import io.tgmcp.telegram.gateway.ConnectionRef;
import io.tgmcp.telegram.gateway.LoginMethod;
import io.tgmcp.telegram.gateway.LoginStage;
import io.tgmcp.telegram.gateway.LoginState;
import io.tgmcp.telegram.gateway.RemoteChat;
import io.tgmcp.telegram.gateway.TelegramAdapterKind;
import io.tgmcp.telegram.gateway.TelegramGateway;
import io.tgmcp.telegram.session.SessionCipher;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Onboarding for the Telegram client session.
 *
 * <p>Codes, passwords and QR tokens arrive here from protected onboarding endpoints, are
 * passed straight to the adapter, and are never persisted, logged or echoed. What is
 * persisted is an encrypted session envelope plus a coarse status.
 */
@Service
public class TelegramConnectionService {

    private final TelegramConnectionRepository connectionRepository;
    private final ChatRepository chatRepository;
    private final TelegramGateway gateway;
    private final SessionCipher sessionCipher;
    private final AuditService auditService;
    private final Clock clock;

    public TelegramConnectionService(TelegramConnectionRepository connectionRepository,
                                     ChatRepository chatRepository,
                                     TelegramGateway gateway,
                                     SessionCipher sessionCipher,
                                     AuditService auditService,
                                     Clock clock) {
        this.connectionRepository = connectionRepository;
        this.chatRepository = chatRepository;
        this.gateway = gateway;
        this.sessionCipher = sessionCipher;
        this.auditService = auditService;
        this.clock = clock;
    }

    public Optional<TelegramConnection> find(AuthenticatedActor actor) {
        return this.connectionRepository.findByOwner(actor.tenantId(), actor.userId());
    }

    @Transactional
    public TelegramConnection getOrCreate(AuthenticatedActor actor) {
        return find(actor).orElseGet(() -> {
            TelegramConnection connection = new TelegramConnection(Ids.newId(), actor.tenantId(),
                    actor.userId(), this.gateway.kind(), LoginStage.DISCONNECTED, null, null,
                    this.sessionCipher.keyId(), null, null, this.clock.instant());
            this.connectionRepository.insert(connection);
            return connection;
        });
    }

    @Transactional
    public LoginState beginLogin(AuthenticatedActor actor, LoginMethod method, String phoneNumber) {
        TelegramConnection connection = getOrCreate(actor);
        requireNoOtherActiveOwner(actor);
        LoginState state = this.gateway.beginLogin(connection.ref(), method, phoneNumber);
        persist(actor, connection, state);
        // Deliberately records the method, never the phone number.
        this.auditService.record(actor, AuditAction.TELEGRAM_CONNECT_STARTED, null, AuditService.ALLOWED,
                Map.of("method", method.name(), "adapter", this.gateway.kind().name()));
        return state;
    }

    @Transactional
    public LoginState submitCode(AuthenticatedActor actor, String code) {
        TelegramConnection connection = getOrCreate(actor);
        LoginState state = this.gateway.submitCode(connection.ref(), code);
        persist(actor, connection, state);
        return state;
    }

    @Transactional
    public LoginState submitPassword(AuthenticatedActor actor, String password) {
        TelegramConnection connection = getOrCreate(actor);
        LoginState state = this.gateway.submitPassword(connection.ref(), password);
        persist(actor, connection, state);
        return state;
    }

    public LoginState status(AuthenticatedActor actor) {
        return find(actor)
                .map(connection -> this.gateway.currentState(connection.ref()))
                .orElse(LoginState.of(LoginStage.DISCONNECTED, null));
    }

    @Transactional
    public void disconnect(AuthenticatedActor actor) {
        find(actor).ifPresent(connection -> {
            this.gateway.disconnect(connection.ref());
            this.connectionRepository.clearSession(actor.tenantId(), connection.id());
            this.auditService.record(actor, AuditAction.TELEGRAM_DISCONNECTED, null, AuditService.ALLOWED,
                    Map.of("adapter", connection.adapter().name()));
        });
    }

    /**
     * Refreshes the list of chats the connected account can see.
     *
     * <p>Discovery is not authorization: a chat appearing here is merely selectable in the
     * UI. Nothing is synced and no message is stored until it is activated with consent.
     */
    @Transactional
    public List<Chat> discoverChats(AuthenticatedActor actor) {
        TelegramConnection connection = getOrCreate(actor);
        if (!connection.ready()) {
            return List.of();
        }
        ConnectionRef ref = connection.ref();
        for (RemoteChat remote : this.gateway.listAvailableChats(ref)) {
            UUID existing = this.chatRepository
                    .findByTgId(actor.tenantId(), connection.id(), remote.tgChatId())
                    .map(Chat::id)
                    .orElseGet(Ids::newId);
            this.chatRepository.upsert(new Chat(existing, actor.tenantId(), connection.id(),
                    remote.tgChatId(), remote.title(), remote.chatType(), remote.username(),
                    remote.memberCount(), null));
        }
        List<Chat> chats = this.chatRepository.findByConnection(actor.tenantId(), connection.id());
        this.auditService.record(actor, AuditAction.CHAT_DISCOVERED, null, AuditService.ALLOWED,
                Map.of("chatsVisible", chats.size()));
        return chats;
    }

    /**
     * F01 mitigation: {@code TdlibTelegramGateway} is one native client shared by the whole
     * process, keyed by nothing — every {@code ConnectionRef} it receives talks to the same
     * underlying Telegram identity. Until that has a real per-owner session manager, letting
     * a second owner start (or continue) a login while another owner's connection is anything
     * but DISCONNECTED would silently mix the two accounts' logins, chat lists and logouts.
     * The mock adapter has no shared native state, so this only applies to TDLIB.
     */
    private void requireNoOtherActiveOwner(AuthenticatedActor actor) {
        if (this.gateway.kind() != TelegramAdapterKind.TDLIB) {
            return;
        }
        if (this.connectionRepository.existsActiveForOtherOwner(actor.tenantId(), actor.userId())) {
            throw new IllegalStateException("validation.singleConnectionOnly");
        }
    }

    private void persist(AuthenticatedActor actor, TelegramConnection connection, LoginState state) {
        this.connectionRepository.updateStatus(actor.tenantId(), connection.id(), state.stage(),
                state.tgUserId(), state.tgUsername(), null);
        if (state.ready()) {
            // Placeholder envelope for the mock adapter; the TDLib adapter keeps its own
            // encrypted database directory and stores its handle the same way.
            byte[] envelope = this.sessionCipher.encrypt(
                    ("session:" + connection.id()).getBytes(StandardCharsets.UTF_8));
            this.connectionRepository.storeSession(actor.tenantId(), connection.id(), envelope,
                    this.sessionCipher.keyId());
            this.auditService.record(actor, AuditAction.TELEGRAM_CONNECTED, null, AuditService.ALLOWED,
                    Map.of("adapter", connection.adapter().name()));
        }
    }
}
