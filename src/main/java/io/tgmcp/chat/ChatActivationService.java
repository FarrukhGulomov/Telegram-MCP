package io.tgmcp.chat;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.tgmcp.audit.AuditAction;
import io.tgmcp.audit.AuditService;
import io.tgmcp.common.Ids;
import io.tgmcp.config.AppProperties;
import io.tgmcp.consent.ConsentCoverage;
import io.tgmcp.consent.ConsentService;
import io.tgmcp.message.MessageRepository;
import io.tgmcp.sync.SyncCheckpointRepository;
import io.tgmcp.telegram.TelegramConnection;
import io.tgmcp.telegram.TelegramConnectionRepository;
import io.tgmcp.telegram.gateway.RemoteParticipant;
import io.tgmcp.telegram.gateway.TelegramGateway;
import io.tgmcp.tenancy.AuthenticatedActor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Activation lifecycle for a chat: propose → collect consent → activate → pause/revoke.
 *
 * <p>Two rules are enforced here rather than left to the UI: a chat cannot become ACTIVE
 * while any required participant lacks consent, and any state that is not allowed to hold
 * content purges that content immediately.
 */
@Service
public class ChatActivationService {

    private final ChatRepository chatRepository;
    private final ChatActivationRepository activationRepository;
    private final ChatParticipantRepository participantRepository;
    private final TelegramConnectionRepository connectionRepository;
    private final MessageRepository messageRepository;
    private final SyncCheckpointRepository checkpointRepository;
    private final ConsentService consentService;
    private final TelegramGateway gateway;
    private final AuditService auditService;
    private final AppProperties properties;
    private final Clock clock;

    public ChatActivationService(ChatRepository chatRepository,
                                 ChatActivationRepository activationRepository,
                                 ChatParticipantRepository participantRepository,
                                 TelegramConnectionRepository connectionRepository,
                                 MessageRepository messageRepository,
                                 SyncCheckpointRepository checkpointRepository,
                                 ConsentService consentService,
                                 TelegramGateway gateway,
                                 AuditService auditService,
                                 AppProperties properties,
                                 Clock clock) {
        this.chatRepository = chatRepository;
        this.activationRepository = activationRepository;
        this.participantRepository = participantRepository;
        this.connectionRepository = connectionRepository;
        this.messageRepository = messageRepository;
        this.checkpointRepository = checkpointRepository;
        this.consentService = consentService;
        this.gateway = gateway;
        this.auditService = auditService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Selects a chat for AI access and imports its participant list, so that the people whose
     * consent is required are known before any content is touched.
     */
    @Transactional
    public ChatActivation propose(AuthenticatedActor actor, UUID chatId, String purpose,
                                  List<String> providers, Instant approvedFrom, Instant approvedTo,
                                  int retentionDays) {
        Chat chat = requireChat(actor, chatId);
        if (approvedTo.isBefore(approvedFrom)) {
            throw new IllegalArgumentException("The approved range must end after it starts");
        }
        if (providers.isEmpty()) {
            throw new IllegalArgumentException("Select at least one provider");
        }
        importParticipants(actor, chat);

        ChatActivation activation = new ChatActivation(
                existingId(actor, chatId), actor.tenantId(), chatId, ActivationState.AWAITING_CONSENT,
                purpose, String.join(",", providers), this.properties.consent().currentVersion(),
                approvedFrom, approvedTo, retentionDays,
                this.participantRepository.membershipFingerprint(actor.tenantId(), chatId),
                "Awaiting participant consent", null, null);
        this.activationRepository.upsert(activation);

        // Nothing may be retained while a chat is merely proposed.
        this.messageRepository.deleteForChat(actor.tenantId(), chatId);
        this.checkpointRepository.reset(actor.tenantId(), chatId);

        this.auditService.record(actor, AuditAction.CHAT_SETTINGS_CHANGED, chatId, AuditService.ALLOWED,
                Map.of("state", ActivationState.AWAITING_CONSENT.name(),
                        "providerScope", providers,
                        "retentionDays", retentionDays,
                        "consentVersion", activation.consentVersion()));
        return activation;
    }

    /** Turns AI access on. Refuses while any required participant is uncovered. */
    @Transactional
    public ChatActivation activate(AuthenticatedActor actor, UUID chatId) {
        ChatActivation activation = require(actor, chatId);
        ConsentCoverage coverage = this.consentService.coverageFor(activation);
        if (!coverage.complete()) {
            this.auditService.record(actor, AuditAction.CHAT_ACTIVATED, chatId, AuditService.DENIED,
                    Map.of("reason", "incomplete consent coverage", "gaps", coverage.gaps().size()));
            throw new IllegalStateException("Cannot activate: " + coverage.gaps().size()
                    + " participant(s) have not consented for this purpose, provider scope and consent version.");
        }
        ChatActivation activated = new ChatActivation(activation.id(), activation.tenantId(),
                activation.chatId(), ActivationState.ACTIVE, activation.purpose(),
                activation.providerScope(), activation.consentVersion(), activation.approvedFrom(),
                activation.approvedTo(), activation.retentionDays(),
                this.participantRepository.membershipFingerprint(actor.tenantId(), chatId), null,
                this.clock.instant(), actor.userId());
        this.activationRepository.upsert(activated);
        this.auditService.record(actor, AuditAction.CHAT_ACTIVATED, chatId, AuditService.ALLOWED,
                Map.of("participantsCovered", coverage.coveredParticipants(),
                        "providerScope", activation.providerScope()));
        return activated;
    }

    @Transactional
    public void pause(AuthenticatedActor actor, UUID chatId, String reason) {
        require(actor, chatId);
        this.activationRepository.updateState(actor.tenantId(), chatId, ActivationState.PAUSED, reason);
        this.auditService.record(actor, AuditAction.CHAT_PAUSED, chatId, AuditService.ALLOWED,
                Map.of("reason", reason));
    }

    @Transactional
    public void resume(AuthenticatedActor actor, UUID chatId) {
        activate(actor, chatId);
    }

    /**
     * Withdraws access. The state change blocks reads on the very next request; the data
     * purge follows in the same transaction and is idempotent if repeated.
     */
    @Transactional
    public void revoke(AuthenticatedActor actor, UUID chatId, String reason) {
        require(actor, chatId);
        this.activationRepository.updateState(actor.tenantId(), chatId, ActivationState.REVOKED, reason);
        this.consentService.revokeChat(actor, chatId, reason);
        int deleted = this.messageRepository.deleteForChat(actor.tenantId(), chatId);
        this.checkpointRepository.reset(actor.tenantId(), chatId);
        this.auditService.record(actor, AuditAction.CHAT_REVOKED, chatId, AuditService.ALLOWED,
                Map.of("reason", reason, "messagesDeleted", deleted));
    }

    /** Explicit "delete my data" without changing the activation state. */
    @Transactional
    public int deleteStoredData(AuthenticatedActor actor, UUID chatId) {
        require(actor, chatId);
        int deleted = this.messageRepository.deleteForChat(actor.tenantId(), chatId);
        this.checkpointRepository.reset(actor.tenantId(), chatId);
        this.auditService.record(actor, AuditAction.DATA_DELETED, chatId, AuditService.ALLOWED,
                Map.of("messagesDeleted", deleted, "requestedBy", "operator"));
        return deleted;
    }

    /**
     * Changing purpose, provider scope or the approved range invalidates the consent that was
     * given for the previous context, so the chat drops back to AWAITING_CONSENT.
     */
    @Transactional
    public ChatActivation updateSettings(AuthenticatedActor actor, UUID chatId, String purpose,
                                         List<String> providers, Instant approvedFrom, Instant approvedTo,
                                         int retentionDays) {
        ChatActivation existing = require(actor, chatId);
        boolean contextChanged = !existing.purpose().equals(purpose)
                || !existing.providerScope().equals(String.join(",", providers))
                || !existing.approvedFrom().equals(approvedFrom)
                || !existing.approvedTo().equals(approvedTo);

        ActivationState state = contextChanged ? ActivationState.AWAITING_CONSENT : existing.state();
        ChatActivation updated = new ChatActivation(existing.id(), existing.tenantId(), chatId, state,
                purpose, String.join(",", providers), this.properties.consent().currentVersion(),
                approvedFrom, approvedTo, retentionDays, existing.membershipFingerprint(),
                contextChanged ? "Processing context changed; consent must be re-collected" : existing.pauseReason(),
                existing.activatedAt(), existing.activatedByUserId());
        this.activationRepository.upsert(updated);

        if (contextChanged) {
            this.messageRepository.deleteForChat(actor.tenantId(), chatId);
            this.checkpointRepository.reset(actor.tenantId(), chatId);
        }
        this.auditService.record(actor, AuditAction.CHAT_SETTINGS_CHANGED, chatId, AuditService.ALLOWED,
                Map.of("contextChanged", contextChanged, "state", state.name(),
                        "retentionDays", retentionDays));
        return updated;
    }

    /** Reads the current participant list from Telegram; needed before consent can be collected. */
    @Transactional
    public List<ChatParticipant> importParticipants(AuthenticatedActor actor, Chat chat) {
        Optional<TelegramConnection> connection =
                this.connectionRepository.find(actor.tenantId(), chat.connectionId());
        if (connection.isEmpty() || !connection.get().ready()) {
            return this.participantRepository.findActive(actor.tenantId(), chat.id());
        }
        List<RemoteParticipant> remote = this.gateway.listParticipants(connection.get().ref(),
                chat.tgChatId());
        for (RemoteParticipant participant : remote) {
            this.participantRepository.upsert(new ChatParticipant(Ids.newId(), actor.tenantId(), chat.id(),
                    participant.tgUserId(), participant.displayName(), participant.bot(), null, null));
        }
        this.participantRepository.markLeft(actor.tenantId(), chat.id(),
                remote.stream().map(RemoteParticipant::tgUserId).toList());
        return this.participantRepository.findActive(actor.tenantId(), chat.id());
    }

    public Optional<ChatActivation> find(AuthenticatedActor actor, UUID chatId) {
        return this.activationRepository.find(actor.tenantId(), chatId);
    }

    private ChatActivation require(AuthenticatedActor actor, UUID chatId) {
        return this.activationRepository.find(actor.tenantId(), chatId)
                .orElseThrow(() -> new IllegalArgumentException("No such chat"));
    }

    private Chat requireChat(AuthenticatedActor actor, UUID chatId) {
        return this.chatRepository.find(actor.tenantId(), chatId)
                .orElseThrow(() -> new IllegalArgumentException("No such chat"));
    }

    private UUID existingId(AuthenticatedActor actor, UUID chatId) {
        return this.activationRepository.find(actor.tenantId(), chatId)
                .map(ChatActivation::id)
                .orElseGet(Ids::newId);
    }
}
