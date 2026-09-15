package io.tgmcp.telegram;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.tgmcp.audit.AuditAction;
import io.tgmcp.audit.AuditService;
import io.tgmcp.chat.ActivationState;
import io.tgmcp.chat.Chat;
import io.tgmcp.chat.ChatActivationService;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final ChatActivationService activationService;
    private final TelegramGateway gateway;
    private final SessionCipher sessionCipher;
    private final AuditService auditService;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public TelegramConnectionService(TelegramConnectionRepository connectionRepository,
                                     ChatRepository chatRepository,
                                     ChatActivationService activationService,
                                     TelegramGateway gateway,
                                     SessionCipher sessionCipher,
                                     AuditService auditService,
                                     Clock clock,
                                     PlatformTransactionManager transactionManager) {
        this.connectionRepository = connectionRepository;
        this.chatRepository = chatRepository;
        this.activationService = activationService;
        this.gateway = gateway;
        this.sessionCipher = sessionCipher;
        this.auditService = auditService;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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
        // Security-audit finding F13: QR-code confirmation happens out of band (the user scans
        // on another device), so it can complete well after this call returns with no request
        // of its own to persist it. Registered before beginLogin() so no transition -- READY
        // included -- can be missed.
        this.gateway.onStateChange(connection.ref(),
                asyncState -> applyAsyncStateChange(actor, connection, asyncState));
        LoginState state = this.gateway.beginLogin(connection.ref(), method, phoneNumber);
        persist(actor, connection, state);
        // Deliberately records the method, never the phone number.
        this.auditService.record(actor, AuditAction.TELEGRAM_CONNECT_STARTED, null, AuditService.ALLOWED,
                Map.of("method", method.name(), "adapter", this.gateway.kind().name()));
        return state;
    }

    /**
     * Applies a login-state change that arrived asynchronously, outside of any UI request --
     * in practice, TDLib reporting {@code authorizationStateReady} (now carrying real account
     * identity; see {@code TdlibTelegramGateway.withAccountIdentity}) once a QR code has been
     * scanned on another device, or an error/logout state reached without a matching submit*
     * call. Idempotent: replaying the same state is harmless, matching {@link #persist}.
     *
     * <p>Deliberately runs its own transaction via {@link TransactionTemplate} rather than
     * {@code @Transactional}: this method is invoked from inside a lambda passed to {@code
     * beginLogin}'s own body, so {@code this} there is the raw target object, not the proxy
     * Spring's annotation-driven transactions depend on -- the exact same self-invocation trap
     * this pass closes elsewhere for {@code AuditService} (security-audit finding F20).
     */
    public void applyAsyncStateChange(AuthenticatedActor actor, TelegramConnection connection, LoginState state) {
        this.transactionTemplate.executeWithoutResult(status -> persist(actor, connection, state));
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
            // Security-audit finding F16: without a live connection there is no way to keep
            // verifying this account's chat membership or consent freshness, so every chat it
            // had activated must stop being MCP-readable the moment it disconnects -- not keep
            // silently serving whatever was already stored, indefinitely, as if nothing had
            // changed. Pausing (not revoking) is deliberate: reconnecting the same account
            // should let an operator resume, not have to re-collect consent for a disconnect
            // that was never a data-deletion event.
            int pausedChats = 0;
            for (Chat chat : this.chatRepository.findByConnection(actor.tenantId(), connection.id())) {
                if (this.activationService.find(actor, chat.id())
                        .map(activation -> activation.state() == ActivationState.ACTIVE)
                        .orElse(false)) {
                    this.activationService.pause(actor, chat.id(), "Telegram connection disconnected");
                    pausedChats++;
                }
            }
            this.auditService.record(actor, AuditAction.TELEGRAM_DISCONNECTED, null, AuditService.ALLOWED,
                    Map.of("adapter", connection.adapter().name(), "chatsPaused", pausedChats));
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
     * F01 mitigation, hardened per security-audit finding SEC-01: {@code TdlibTelegramGateway}
     * is one native client shared by the whole process, keyed by nothing on its own -- every
     * {@code ConnectionRef} it receives talks to the same underlying Telegram identity unless
     * the gateway itself rejects a mismatched one (see {@code TdlibTelegramGateway
     * .requireCurrentOwner}, the other half of this fix). Until a real per-owner session
     * manager exists, letting a second owner start (or continue) a login while another owner's
     * connection is anything but DISCONNECTED would silently mix the two accounts' logins, chat
     * lists and logouts.
     *
     * <p>{@code acquireSingleOwnerLease()} must run first, inside the same transaction as this
     * check and everything {@code beginLogin} does afterward: the {@code SELECT EXISTS} below
     * was previously not atomic with that surrounding transaction at all, so two concurrent
     * {@code beginLogin} calls could both observe "no other active owner" before either had
     * committed. The advisory lock it takes serializes every caller of this method against
     * every other, cluster-wide, released automatically when this transaction ends -- the
     * second caller blocks here until the first has fully committed (or rolled back), then
     * re-reads current, authoritative state.
     *
     * <p>The mock adapter has no shared native state, so this only applies to TDLIB.
     */
    private void requireNoOtherActiveOwner(AuthenticatedActor actor) {
        if (this.gateway.kind() != TelegramAdapterKind.TDLIB) {
            return;
        }
        this.connectionRepository.acquireSingleOwnerLease();
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
