package io.tgmcp.access;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.tgmcp.chat.ActivationState;
import io.tgmcp.chat.Chat;
import io.tgmcp.chat.ChatActivation;
import io.tgmcp.chat.ChatActivationRepository;
import io.tgmcp.chat.ChatRepository;
import io.tgmcp.config.AppProperties;
import io.tgmcp.consent.ConsentCoverage;
import io.tgmcp.consent.ConsentService;
import io.tgmcp.sync.SyncCheckpoint;
import io.tgmcp.sync.SyncCheckpointRepository;
import io.tgmcp.telegram.TelegramConnectionRepository;
import io.tgmcp.telegram.gateway.LoginStage;
import io.tgmcp.tenancy.AuthenticatedActor;

import org.springframework.stereotype.Service;

/**
 * The single place where "may this caller read this chat right now?" is answered.
 *
 * <p>Every access path — MCP tools, the management UI, and the sync worker — goes through
 * this service, so a policy change cannot be applied to one path and forgotten in another.
 *
 * <p>The check is intentionally cheap enough to run twice per request: once before querying
 * and once again immediately before a response is serialised, so that a revocation that
 * lands mid-request still blocks the response.
 */
@Service
public class ChatAccessService {

    private final ChatRepository chatRepository;
    private final ChatActivationRepository activationRepository;
    private final ConsentService consentService;
    private final SyncCheckpointRepository checkpointRepository;
    private final TelegramConnectionRepository connectionRepository;
    private final Duration membershipFreshnessBudget;
    private final Clock clock;

    public ChatAccessService(ChatRepository chatRepository,
                             ChatActivationRepository activationRepository,
                             ConsentService consentService,
                             SyncCheckpointRepository checkpointRepository,
                             TelegramConnectionRepository connectionRepository,
                             AppProperties properties,
                             Clock clock) {
        this.chatRepository = chatRepository;
        this.activationRepository = activationRepository;
        this.consentService = consentService;
        this.checkpointRepository = checkpointRepository;
        this.connectionRepository = connectionRepository;
        this.membershipFreshnessBudget = properties.sync().membershipFreshnessBudget();
        this.clock = clock;
    }

    /**
     * Chats this caller may read. Only ACTIVE chats with complete consent coverage for the
     * requesting provider are ever listed; everything else is simply absent.
     *
     * <p>Security-audit finding SEC-06: a chat that HAS synced before but has gone stale
     * beyond the freshness budget (lost upstream authorization with no way to reconfirm
     * membership) is excluded the same way {@link #authorize} excludes it for reading. A chat
     * that has simply never synced yet is left alone here -- that is the existing, separate
     * {@code requireSynced}/{@code SYNC_PENDING} concept {@link #authorize} already handles
     * per call, not a loss of something this listing previously had.
     */
    public List<AuthorizedChat> listAllowed(AuthenticatedActor actor, String provider) {
        List<AuthorizedChat> allowed = new ArrayList<>();
        for (ChatActivation activation : this.activationRepository.findByState(actor.tenantId(),
                ActivationState.ACTIVE)) {
            Optional<Chat> chat = this.chatRepository.find(actor.tenantId(), activation.chatId());
            if (chat.isEmpty() || !activation.coversProvider(provider)) {
                continue;
            }
            ConsentCoverage coverage = this.consentService.coverageFor(activation);
            if (!coverage.complete()) {
                continue;
            }
            SyncCheckpoint checkpoint = this.checkpointRepository
                    .find(actor.tenantId(), activation.chatId()).orElse(null);
            if (checkpoint != null && checkpoint.everSucceeded() && membershipStale(checkpoint)) {
                continue;
            }
            if (!connectionReady(actor.tenantId())) {
                continue;
            }
            allowed.add(new AuthorizedChat(chat.get(), activation, coverage, checkpoint));
        }
        return List.copyOf(allowed);
    }

    /**
     * Authorizes one chat, or throws.
     *
     * @param requireSynced when true, a chat that has never completed a sync pass yields
     *                      {@code SYNC_PENDING} rather than an empty, misleading result
     */
    public AuthorizedChat authorize(AuthenticatedActor actor, UUID chatId, String provider, boolean requireSynced) {
        if (chatId == null) {
            throw ChatAccessException.denied();
        }
        Chat chat = this.chatRepository.find(actor.tenantId(), chatId).orElseThrow(ChatAccessException::denied);
        ChatActivation activation = this.activationRepository.find(actor.tenantId(), chatId)
                .orElseThrow(ChatAccessException::denied);

        // A chat that was never opted in, or was switched off, is indistinguishable from a
        // chat that does not exist.
        if (activation.state() == ActivationState.INACTIVE || activation.state() == ActivationState.REVOKED) {
            throw ChatAccessException.denied();
        }
        if (!activation.coversProvider(provider)) {
            throw ChatAccessException.denied();
        }

        ConsentCoverage coverage = this.consentService.coverageFor(activation);

        if (activation.state() == ActivationState.AWAITING_CONSENT) {
            throw ChatAccessException.consentRequired(
                    "This chat is awaiting participant consent and cannot be read yet.");
        }
        if (activation.state() == ActivationState.PAUSED) {
            if (!coverage.complete()) {
                throw ChatAccessException.consentRequired(
                        "Access is paused because participant consent coverage is incomplete or uncertain.");
            }
            throw ChatAccessException.denied();
        }
        // ACTIVE, but consent is re-evaluated against the *current* membership every time.
        if (!coverage.complete()) {
            throw ChatAccessException.consentRequired(
                    "Access is paused because participant consent coverage is incomplete or uncertain.");
        }

        SyncCheckpoint checkpoint = this.checkpointRepository.find(actor.tenantId(), chatId).orElse(null);
        if (requireSynced) {
            if (checkpoint == null || !checkpoint.everSucceeded()) {
                throw ChatAccessException.syncPending(
                        "This chat has been approved but no messages have been synchronised yet.");
            }
            // Security-audit finding SEC-06: coverage above is only ever computed from the
            // participant list last confirmed by a successful sync. If upstream authorization
            // is lost -- the Telegram account is logged out, banned, or loses permission on
            // this chat -- that list can never be reconfirmed again, yet nothing stopped reads
            // from trusting it forever. Once the last confirmation is older than the
            // configured budget, this chat stops being readable until sync succeeds again,
            // exactly as if membership were currently unknown.
            if (membershipStale(checkpoint)) {
                throw ChatAccessException.consentRequired(
                        "Membership could not be reconfirmed recently enough to trust; this chat will become "
                                + "readable again once synchronisation succeeds.");
            }
            // Security-audit finding SEC-R03: the freshness check above only ever catches
            // staleness that has already accumulated for a full budget window (default 24h) --
            // it says nothing about whether the connection is live *right now*. A connection that
            // has lost authorization for any other reason (a remote logout, a ban, an explicit
            // disconnect) can otherwise leave a chat fully ACTIVE and still "fresh enough" by the
            // clock while nothing could actually reconfirm membership or permissions if it needed
            // to. Blocking reads the instant the connection is known not to be READY closes that
            // gap immediately rather than waiting out the freshness budget. (Security-audit
            // finding H1: a returning account's own fresh-login attempt used to also trigger this
            // -- PreAuthLoginService#begin's prepareForFreshLogin tore the real connection down
            // before re-authenticating it -- but that no longer happens at all; see
            // TelegramConnectionService#beginShadowLogin.)
            if (!connectionReady(actor.tenantId())) {
                throw ChatAccessException.connectionUnavailable(
                        "This account's Telegram connection is not currently active; this chat will become "
                                + "readable again once it reconnects.");
            }
        }
        return new AuthorizedChat(chat, activation, coverage, checkpoint);
    }

    private boolean membershipStale(SyncCheckpoint checkpoint) {
        if (checkpoint == null || !checkpoint.everSucceeded()) {
            return true;
        }
        return Duration.between(checkpoint.lastSuccessAt(), this.clock.instant())
                .compareTo(this.membershipFreshnessBudget) > 0;
    }

    private boolean connectionReady(UUID tenantId) {
        return this.connectionRepository.findByTenant(tenantId)
                .map(connection -> connection.status() == LoginStage.READY)
                .orElse(false);
    }

    /**
     * Authorizes several chats at once for search. Unknown or unauthorized ids are rejected
     * as a group with a single {@code ACCESS_DENIED} so that a caller cannot binary-search
     * for which of its guesses exist.
     */
    public Map<UUID, AuthorizedChat> authorizeAll(AuthenticatedActor actor, Collection<UUID> chatIds,
                                                  String provider, boolean requireSynced) {
        Map<UUID, AuthorizedChat> authorized = new LinkedHashMap<>();
        for (UUID chatId : chatIds) {
            authorized.put(chatId, authorize(actor, chatId, provider, requireSynced));
        }
        return authorized;
    }

    /**
     * Re-runs the authorization check immediately before a response is emitted, and confirms
     * the chat's fencing epoch and processing-context revision are still exactly what they
     * were when the caller first authorized it at the start of the request.
     *
     * <p>Security-audit finding SEC-05: a plain re-check of "is this currently allowed" is
     * vulnerable to an ABA cycle -- revoke, re-propose, reconsent and reactivate can all
     * complete within the lifetime of one slow request, leaving a fresh state-and-coverage
     * check fully satisfied even though the response being built was buffered under an
     * authorization instance that no longer exists. Comparing epoch and contextRevision
     * against the snapshot taken at query start catches that the same way {@code
     * ChatSyncService#requireNotFenced} already does for the write path (security audit
     * finding F07): either value changing means the chat's authorization was torn down and
     * rebuilt while this request was in flight, which must reject the buffered response even
     * though the chat is, again, currently readable.
     */
    public void reverify(AuthenticatedActor actor, Collection<AuthorizedChat> originallyAuthorized,
                         String provider) {
        for (AuthorizedChat original : originallyAuthorized) {
            AuthorizedChat fresh = authorize(actor, original.chat().id(), provider, false);
            if (fresh.activation().epoch() != original.activation().epoch()
                    || !fresh.activation().contextRevision().equals(original.activation().contextRevision())) {
                throw ChatAccessException.denied();
            }
        }
    }

    public Instant now() {
        return this.clock.instant();
    }
}
