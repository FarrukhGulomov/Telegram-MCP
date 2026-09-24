package io.tgmcp.telegram;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.tgmcp.audit.AuditAction;
import io.tgmcp.audit.AuditService;
import io.tgmcp.chat.ActivationState;
import io.tgmcp.chat.Chat;
import io.tgmcp.chat.ChatActivationService;
import io.tgmcp.chat.ChatRepository;
import io.tgmcp.common.Ids;
import io.tgmcp.tenancy.ActorType;
import io.tgmcp.tenancy.AuthenticatedActor;
import io.tgmcp.telegram.gateway.ConnectionRef;
import io.tgmcp.telegram.gateway.LoginMethod;
import io.tgmcp.telegram.gateway.LoginStage;
import io.tgmcp.telegram.gateway.LoginState;
import io.tgmcp.telegram.gateway.RemoteChat;
import io.tgmcp.telegram.gateway.TelegramAuthException;
import io.tgmcp.telegram.gateway.TelegramGateway;
import io.tgmcp.telegram.session.SessionCipher;

import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
    /**
     * Security-audit finding H1: in-memory progress for a shadow login attempt (see {@link
     * #beginShadowLogin}), keyed by its own {@code loginSlot} -- deliberately never a database
     * table, since a shadow attempt has no lasting existence of its own: it either gets promoted
     * into this account's real connection row ({@link #promoteShadowLogin}) or is discarded
     * ({@link #discardShadowLogin}) without a trace, same as every other not-yet-READY login
     * this application has never persisted.
     */
    private final java.util.concurrent.ConcurrentHashMap<UUID, LoginState> shadowStates =
            new java.util.concurrent.ConcurrentHashMap<>();

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
                    actor.userId(), this.gateway.kind(), LoginStage.DISCONNECTED, null, null, null,
                    this.sessionCipher.keyId(), null, null, this.clock.instant());
            this.connectionRepository.insert(connection);
            return connection;
        });
    }

    @Transactional
    public LoginState beginLogin(AuthenticatedActor actor, LoginMethod method, String phoneNumber) {
        TelegramConnection connection = getOrCreate(actor);
        if (connection.ready()) {
            // Real-account testing hit this directly: a container restart silently restores an
            // already-authorized TDLib session from its persisted local database (no login
            // needed), but the connect form has no way to know that without asking, so a
            // resubmitted login attempt reached TDLib as setAuthenticationPhoneNumber, which
            // correctly refused it with the protocol-level "Call to setAuthenticationPhoneNumber
            // unexpected" -- a confusing error for something that was never actually a failure.
            return LoginState.of(LoginStage.READY, "login.tdlib.ready");
        }
        // Security-audit finding F13: QR-code confirmation happens out of band (the user scans
        // on another device), so it can complete well after this call returns with no request
        // of its own to persist it. Registered before beginLogin() so no transition -- READY
        // included -- can be missed.
        reclaimOwnership(actor, connection);
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

    /**
     * Confirmed from live production logs: {@code TdlibTelegramGateway.currentOwner} is a
     * volatile, in-memory field reset to {@code null} on every process restart, but every
     * gateway method except {@code beginLogin}/{@code onStateChange} requires it to match --
     * {@link #status} and {@link #disconnect} each closed this gap reactively for themselves,
     * but every other method a page load can reach (in practice, {@code
     * ChatActivationService.importParticipants} calling {@code listParticipants} the moment an
     * operator merely opens a chat page) had no such fallback and threw {@link
     * TelegramAuthException} instead, an unhandled error bouncing the operator back to the
     * dashboard. Reclaiming ownership once here, right after startup, for whichever connection's
     * own persisted row proves it previously reached a real {@code READY} login, means none of
     * those other call sites need their own copy of this fix -- by the time the first request
     * arrives, ownership is already exactly what it was before the restart.
     *
     * <p>Registers the same real listener {@link #beginLogin} would (routed through a
     * synthesized {@link ActorType#SYSTEM} actor, since no authenticated request exists yet at
     * startup) rather than a no-op, so an async state change TDLib pushes before any operator
     * revisits the connect page -- a remote logout or ban -- still reaches {@link
     * #applyAsyncStateChange} and pauses this connection's active chats (security-audit finding
     * SEC-06), instead of silently going nowhere.
     *
     * <p>Confirmed from production logs: firing on {@code ApplicationReadyEvent} (Spring's own
     * "the app is fully up" signal) was too late -- {@code @Scheduled} tasks such as {@link
     * io.tgmcp.sync.SyncScheduler} are armed earlier, on {@code ContextRefreshedEvent}, and a
     * fixed-delay task with no explicit initial delay fires its first execution essentially
     * immediately once armed. That first sync tick reached {@code requireCurrentOwner} before
     * this method had run at all, so {@code currentOwner} was still whatever a fresh process
     * starts with (nothing), and the sync failed with "not the current owner" -- indistinguishable
     * from a genuine SEC-01 rejection, so it was treated as a non-retryable auth failure and
     * halted every newly activated chat on the very first attempt, forever, until an operator
     * happened to notice and press "Sync now". Listening on {@code ContextRefreshedEvent} instead,
     * with the highest listener precedence, makes this run synchronously as part of the very same
     * event dispatch that arms the scheduler ({@code ScheduledAnnotationBeanPostProcessor} listens
     * to the same event, at default/lowest precedence) -- ownership is reclaimed before that
     * dispatch even finishes, and only then can the scheduler's own timer thread get a chance to
     * invoke anything, closing the race for good rather than relying on it usually resolving fast
     * enough.
     */
    @EventListener(ContextRefreshedEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void reclaimOwnershipOnStartup() {
        for (TelegramConnection connection : this.connectionRepository.findAllReady()) {
            AuthenticatedActor systemActor = new AuthenticatedActor(connection.tenantId(),
                    connection.ownerUserId(), "system", ActorType.SYSTEM, Set.of(), null);
            this.gateway.onStateChange(connection.ref(),
                    asyncState -> applyAsyncStateChange(systemActor, connection, asyncState));
        }
    }

    @Transactional
    public LoginState submitCode(AuthenticatedActor actor, String code) {
        TelegramConnection connection = getOrCreate(actor);
        reclaimOwnership(actor, connection);
        LoginState state = this.gateway.submitCode(connection.ref(), code);
        persist(actor, connection, state);
        return state;
    }

    @Transactional
    public LoginState resendCode(AuthenticatedActor actor) {
        TelegramConnection connection = getOrCreate(actor);
        reclaimOwnership(actor, connection);
        LoginState state = this.gateway.resendCode(connection.ref());
        persist(actor, connection, state);
        return state;
    }

    @Transactional
    public LoginState submitPassword(AuthenticatedActor actor, String password) {
        TelegramConnection connection = getOrCreate(actor);
        reclaimOwnership(actor, connection);
        LoginState state = this.gateway.submitPassword(connection.ref(), password);
        persist(actor, connection, state);
        return state;
    }

    /**
     * Confirmed from live production logs: {@code TdlibTelegramGateway.currentOwner} (and, since
     * the per-tenant TDLib routing refactor, the very existence of that tenant's {@code
     * TdlibTelegramGateway} instance) is in-memory only and gone after any restart -- a resend
     * or code/password submission reaching a freshly (re)created instance whose {@code
     * currentOwner} is still {@code null} threw {@link TelegramAuthException} outright,
     * surfacing as an unhandled error instead of the ordinary retry it actually is. Re-registers
     * the same real listener {@link #beginLogin} does (not a no-op, unlike {@link
     * #reclaimOwnershipIfPersistedAsOwner}'s use for {@link #disconnect}) so a later async
     * transition -- in particular the {@code authorizationStateReady} that submitting a correct
     * code or password triggers moments after this call returns -- still reaches {@link
     * #applyAsyncStateChange} and gets persisted, rather than silently going nowhere.
     * Idempotent when ownership was never lost: {@code onStateChange} just re-affirms the same
     * ref and re-sends TDLib parameters only if they were not already sent for this epoch.
     */
    private void reclaimOwnership(AuthenticatedActor actor, TelegramConnection connection) {
        this.gateway.onStateChange(connection.ref(),
                asyncState -> applyAsyncStateChange(actor, connection, asyncState));
    }

    public LoginState status(AuthenticatedActor actor) {
        return find(actor)
                .map(this::liveOrPersistedState)
                .orElse(LoginState.of(LoginStage.DISCONNECTED, null));
    }

    /**
     * {@code gateway.currentState(ref)} can only ever answer for whichever ref currently owns
     * the one shared TDLib session (finding SEC-01); every ref that is not the current owner --
     * not just an unrelated tenant, but this exact same connection after any process restart,
     * since {@code TdlibTelegramGateway.currentOwner} is a volatile, never-persisted field reset
     * to null on every startup -- gets {@link TelegramAuthException} instead of an answer.
     * {@code beginLogin} already special-cased its own version of this (a container restart
     * silently restores TDLib's already-authorized session from disk with no login needed); this
     * is the same fix for the read-only path every plain page view takes. The persisted stage is
     * the best information available once ownership cannot be asserted -- only a currently-owned
     * gateway can offer anything more (a not-yet-persisted transition, a live QR link) -- so this
     * falls back to it for any stage, not just READY, rather than letting the exception surface
     * as an unhandled error for an account whose actual connection state is known and fine.
     */
    private LoginState liveOrPersistedState(TelegramConnection connection) {
        try {
            return this.gateway.currentState(connection.ref());
        }
        catch (TelegramAuthException notCurrentOwner) {
            return LoginState.of(connection.status(), null);
        }
    }

    @Transactional
    public void disconnect(AuthenticatedActor actor) {
        find(actor).ifPresent(connection -> {
            reclaimOwnershipIfPersistedAsOwner(connection);
            this.gateway.disconnect(connection.ref());
            this.connectionRepository.clearSession(actor.tenantId(), connection.id());
            int pausedChats = pauseActiveChatsForConnection(actor, connection, "Telegram connection disconnected");
            this.auditService.record(actor, AuditAction.TELEGRAM_DISCONNECTED, null, AuditService.ALLOWED,
                    Map.of("adapter", connection.adapter().name(), "chatsPaused", pausedChats));
        });
    }

    /**
     * Security-audit finding H3: a brand-new tenant abandoned before ever reaching a real login
     * (an explicit {@code /login/cancel}, or {@code AbandonedSignupReaper}'s own scheduled sweep)
     * has no session worth ending gracefully through {@link #disconnect} -- there was never a
     * confirmed owner to reclaim, only whatever native client {@link TelegramGateway#beginLogin}
     * may already have started. Tells the gateway to discard that tenant's resources outright.
     */
    public void discardAbandonedSignup(java.util.UUID tenantId) {
        this.gateway.discardTenant(tenantId);
    }

    /**
     * Security-audit finding H1: an already-registered account's fresh login attempt (phone
     * number or QR) used to tear down whichever session that account currently held -- {@code
     * TelegramConnectionService#prepareForFreshLogin}, called from {@code
     * PreAuthLoginService#begin} before anyone had proven anything -- on the reasoning that
     * Telegram has no way to re-verify a phone number that already owns an authorized session,
     * so the old one had to end before a new login could even begin. That reasoning is true of a
     * single native client, but nothing required reusing the account's <em>own</em> client for an
     * attempt that has not yet proven it deserves to replace it: anyone who merely knew a victim's
     * phone number could disconnect their live session and pause every chat it had activated,
     * without ever completing a login of their own.
     *
     * <p>This runs the whole attempt -- {@link #beginLogin} through the eventual READY -- against
     * an isolated shadow session instead, keyed by a freshly minted {@code loginSlot} distinct
     * from this account's own {@link ConnectionRef#routingKey()} (see that field's own javadoc for
     * how an adapter uses it). The account's real session, if it has one, is never touched: not
     * disconnected, not persisted over, not even asked a single question. Nothing is written to
     * this connection's own database row until {@link #promoteShadowLogin} adopts a
     * <em>confirmed</em> new session as the real one -- see {@code
     * PreAuthLoginService#finishIfReady} for the identity check that must pass first.
     */
    @Transactional
    public LoginState beginShadowLogin(AuthenticatedActor actor, LoginMethod method, String phoneNumber,
                                       UUID loginSlot) {
        TelegramConnection connection = getOrCreate(actor);
        ConnectionRef shadowRef = new ConnectionRef(connection.tenantId(), connection.id(), loginSlot);
        // No persistence, unlike reclaimOwnership's identical-looking call for the real ref: an
        // async push (a QR scan completing out of band) for a shadow login only ever needs to
        // reach shadowStates below, never the database.
        this.gateway.onStateChange(shadowRef, asyncState -> this.shadowStates.put(loginSlot, asyncState));
        LoginState state = this.gateway.beginLogin(shadowRef, method, phoneNumber);
        this.shadowStates.put(loginSlot, state);
        // Deliberately records the method, never the phone number -- same as beginLogin's own
        // audit entry.
        this.auditService.record(actor, AuditAction.TELEGRAM_CONNECT_STARTED, null, AuditService.ALLOWED,
                Map.of("method", method.name(), "adapter", this.gateway.kind().name(), "shadow", true));
        return state;
    }

    @Transactional
    public LoginState submitShadowCode(AuthenticatedActor actor, UUID loginSlot, String code) {
        LoginState state = this.gateway.submitCode(shadowRef(actor, loginSlot), code);
        this.shadowStates.put(loginSlot, state);
        return state;
    }

    @Transactional
    public LoginState resendShadowCode(AuthenticatedActor actor, UUID loginSlot) {
        LoginState state = this.gateway.resendCode(shadowRef(actor, loginSlot));
        this.shadowStates.put(loginSlot, state);
        return state;
    }

    @Transactional
    public LoginState submitShadowPassword(AuthenticatedActor actor, UUID loginSlot, String password) {
        LoginState state = this.gateway.submitPassword(shadowRef(actor, loginSlot), password);
        this.shadowStates.put(loginSlot, state);
        return state;
    }

    /**
     * The shadow login's own progress, tracked purely in memory (see {@link #shadowStates}) --
     * unlike {@link #status}, there is no persisted row to fall back on for a session that, by
     * design, is never written to this account's connection row until {@link
     * #promoteShadowLogin} adopts it. A restart mid-attempt loses this the same way it already
     * loses any other not-yet-READY login's progress; the visitor simply retries.
     */
    public LoginState shadowStatus(UUID loginSlot) {
        return this.shadowStates.getOrDefault(loginSlot, LoginState.of(LoginStage.DISCONNECTED, null));
    }

    private ConnectionRef shadowRef(AuthenticatedActor actor, UUID loginSlot) {
        TelegramConnection connection = getOrCreate(actor);
        return new ConnectionRef(connection.tenantId(), connection.id(), loginSlot);
    }

    /**
     * Adopts a shadow login's already-confirmed-good session (see {@code
     * PreAuthLoginService#finishIfReady}'s identity check, which must pass before this is ever
     * called) as this account's real, live session -- the one and only point at which a shadow
     * attempt is allowed to affect the account's own connection row or displace whatever session
     * it already had.
     *
     * <p>Confirmed from live production logs: {@link TelegramGateway#promoteSession} only ever
     * re-keys an adapter's own routing map (see {@code RoutedTdlibTelegramGateway}'s own javadoc)
     * -- it does not, and structurally cannot from outside, touch the promoted {@code
     * TdlibTelegramGateway} instance's own internal {@code currentOwner}/listener state, which is
     * still whatever the shadow login itself set: the shadow ref (a different {@link
     * ConnectionRef#routingKey()}) and a no-op listener (see {@link #beginShadowLogin}). Every
     * ordinary call this account makes afterward uses its normal ref (routingKey == tenantId),
     * which {@code requireCurrentOwner} then rejects outright as "not the current owner" -- not a
     * hypothetical: this broke every post-promotion call (in practice, the very next chat
     * discovery) the first time a real re-login went through this path. {@link
     * #reclaimOwnership} is exactly the fix already used everywhere else a ref needs to (re)claim
     * ownership of whatever client currently answers to its routing key -- calling it here, now
     * that {@link TelegramGateway#promoteSession} has already made that key resolve to the
     * promoted client, re-establishes both the correct owner and the real listener future async
     * transitions (a remote logout, SEC-06) need to keep reaching {@link #applyAsyncStateChange}.
     */
    @Transactional
    public void promoteShadowLogin(AuthenticatedActor actor, UUID loginSlot, LoginState confirmedState) {
        TelegramConnection connection = getOrCreate(actor);
        this.gateway.promoteSession(new ConnectionRef(connection.tenantId(), connection.id(), loginSlot),
                connection.tenantId());
        reclaimOwnership(actor, connection);
        this.shadowStates.remove(loginSlot);
        persist(actor, connection, confirmedState);
    }

    /**
     * Discards an abandoned or rejected shadow login attempt -- an explicit {@code
     * /login/cancel}, an identity mismatch, or a duplicate-identity rollback -- without touching
     * whatever real session this account already has, if any. See {@link #discardAbandonedSignup}
     * for the sibling case (a brand-new tenant abandoned before any login at all) this shares its
     * gateway-level implementation with.
     */
    public void discardShadowLogin(UUID loginSlot) {
        this.shadowStates.remove(loginSlot);
        this.gateway.discardTenant(loginSlot);
    }

    /**
     * Confirmed from live production logs: the same restart-resilience gap {@link #status}
     * already closes also broke disconnect() -- gateway.disconnect(ref) throws {@link
     * TelegramAuthException} ("not the current owner") for a ref no one has reclaimed since the
     * last restart, aborting the whole disconnect (including the local cleanup below) for the
     * one action an operator most needs to work on a real, previously-connected account.
     *
     * <p>Reclaims ownership for this ref only when its own persisted row proves it previously
     * engaged in a real login (anything but {@code DISCONNECTED}) -- never unconditionally,
     * which would claim a shared native session on behalf of a tenant who merely visited
     * {@code /connect} (a still-{@code DISCONNECTED} row) with nothing of their own to reclaim.
     * Each tenant now has its own {@code TdlibTelegramGateway} instance (per-tenant TDLib
     * routing), so this can never conflict with a different tenant's ownership regardless.
     */
    private void reclaimOwnershipIfPersistedAsOwner(TelegramConnection connection) {
        if (connection.status() != LoginStage.DISCONNECTED) {
            this.gateway.onStateChange(connection.ref(), state -> { });
        }
    }

    /**
     * Security-audit finding F16: without a live connection there is no way to keep verifying
     * this account's chat membership or consent freshness, so every chat it had activated must
     * stop being MCP-readable the moment the connection is lost -- not keep silently serving
     * whatever was already stored, indefinitely, as if nothing had changed. Pausing (not
     * revoking) is deliberate: reconnecting the same account should let an operator resume,
     * not have to re-collect consent for a disconnect that was never a data-deletion event.
     *
     * <p>Security-audit finding SEC-06 broadens this beyond the explicit {@link #disconnect}
     * call: {@link #applyAsyncStateChange} reaches this too, for a connection that TDLib
     * itself reports as logged out, closed, or erroring -- the account was banned, logged out
     * remotely from another device, or otherwise lost authorization with no user action here
     * at all. That loss is exactly as blinding to future membership/consent verification as an
     * explicit disconnect, so it must pause access exactly the same way.
     */
    private int pauseActiveChatsForConnection(AuthenticatedActor actor, TelegramConnection connection,
                                              String reason) {
        int pausedChats = 0;
        for (Chat chat : this.chatRepository.findByConnection(actor.tenantId(), connection.id())) {
            if (this.activationService.find(actor, chat.id())
                    .map(activation -> activation.state() == ActivationState.ACTIVE)
                    .orElse(false)) {
                this.activationService.pause(actor, chat.id(), reason);
                pausedChats++;
            }
        }
        return pausedChats;
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

    private void persist(AuthenticatedActor actor, TelegramConnection connection, LoginState state) {
        this.connectionRepository.updateStatus(actor.tenantId(), connection.id(), state.stage(),
                state.tgUserId(), state.tgUsername(), state.tgPhoneNumber(), null);
        if (state.ready()) {
            // Placeholder envelope for the mock adapter; the TDLib adapter keeps its own
            // encrypted database directory and stores its handle the same way.
            byte[] envelope = this.sessionCipher.encrypt(
                    ("session:" + connection.id()).getBytes(StandardCharsets.UTF_8),
                    actor.tenantId(), connection.id());
            this.connectionRepository.storeSession(actor.tenantId(), connection.id(), envelope,
                    this.sessionCipher.keyId());
            this.auditService.record(actor, AuditAction.TELEGRAM_CONNECTED, null, AuditService.ALLOWED,
                    Map.of("adapter", connection.adapter().name()));
        }
        else if (state.stage() == LoginStage.ERROR
                || (state.stage() == LoginStage.DISCONNECTED && !"login.tdlib.initialising".equals(state.message()))) {
            // Security-audit finding SEC-06: this state can arrive with no user action at all
            // -- TDLib reporting authorizationStateLoggingOut/Closing/Closed, or any other
            // unrecognised state mapped to ERROR -- meaning the account was logged out
            // remotely, banned, or otherwise lost authorization out of band. That is exactly
            // as blinding to future membership/consent verification as calling disconnect()
            // explicitly, so it must pause every chat this connection had activated the same
            // way (see pauseActiveChatsForConnection).
            //
            // Confirmed from production logs: reclaimOwnershipOnStartup() registers this same
            // listener at boot for every already-READY connection, so it also receives TDLib's
            // authorizationStateWaitTdlibParameters push -- a normal, transient step every
            // fresh process goes through, mapped to DISCONNECTED purely to drive UI copy during
            // a live login flow (see TdlibTelegramGateway.mapAuthorizationState). Treating that
            // as a genuine authorization loss meant every restart paused every active chat, even
            // though the account was never actually logged out. Excluding that specific message
            // keeps SEC-06's protection for real disconnects while no longer punishing a normal
            // restart.
            int pausedChats = pauseActiveChatsForConnection(actor, connection,
                    "Telegram authorization was lost (" + state.stage() + ")");
            if (pausedChats > 0) {
                this.auditService.record(actor, AuditAction.TELEGRAM_DISCONNECTED, null, AuditService.ALLOWED,
                        Map.of("adapter", connection.adapter().name(), "chatsPaused", pausedChats,
                                "reason", "async authorization loss: " + state.stage()));
            }
        }
    }
}
