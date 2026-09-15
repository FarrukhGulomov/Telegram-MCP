package io.tgmcp.access;

import java.time.Clock;
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
import io.tgmcp.consent.ConsentCoverage;
import io.tgmcp.consent.ConsentService;
import io.tgmcp.sync.SyncCheckpoint;
import io.tgmcp.sync.SyncCheckpointRepository;
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
    private final Clock clock;

    public ChatAccessService(ChatRepository chatRepository,
                             ChatActivationRepository activationRepository,
                             ConsentService consentService,
                             SyncCheckpointRepository checkpointRepository,
                             Clock clock) {
        this.chatRepository = chatRepository;
        this.activationRepository = activationRepository;
        this.consentService = consentService;
        this.checkpointRepository = checkpointRepository;
        this.clock = clock;
    }

    /**
     * Chats this caller may read. Only ACTIVE chats with complete consent coverage for the
     * requesting provider are ever listed; everything else is simply absent.
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
        if (requireSynced && (checkpoint == null || !checkpoint.everSucceeded())) {
            throw ChatAccessException.syncPending(
                    "This chat has been approved but no messages have been synchronised yet.");
        }
        return new AuthorizedChat(chat, activation, coverage, checkpoint);
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
