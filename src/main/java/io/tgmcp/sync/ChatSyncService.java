package io.tgmcp.sync;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.tgmcp.audit.AuditAction;
import io.tgmcp.audit.AuditService;
import io.tgmcp.chat.ActivationState;
import io.tgmcp.chat.Chat;
import io.tgmcp.chat.ChatActivation;
import io.tgmcp.chat.ChatActivationRepository;
import io.tgmcp.chat.ChatParticipant;
import io.tgmcp.chat.ChatParticipantRepository;
import io.tgmcp.chat.ChatRepository;
import io.tgmcp.common.Ids;
import io.tgmcp.config.AppProperties;
import io.tgmcp.consent.ConsentCoverage;
import io.tgmcp.consent.ConsentService;
import io.tgmcp.message.MessageRepository;
import io.tgmcp.message.StoredMessage;
import io.tgmcp.telegram.TelegramConnection;
import io.tgmcp.telegram.TelegramConnectionRepository;
import io.tgmcp.telegram.gateway.ConnectionRef;
import io.tgmcp.telegram.gateway.MessageWindow;
import io.tgmcp.telegram.gateway.RemoteChat;
import io.tgmcp.telegram.gateway.RemoteMessage;
import io.tgmcp.telegram.gateway.RemoteMessagePage;
import io.tgmcp.telegram.gateway.RemoteParticipant;
import io.tgmcp.telegram.gateway.TelegramFloodWaitException;
import io.tgmcp.telegram.gateway.TelegramGateway;
import io.tgmcp.telegram.gateway.TelegramGatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

/**
 * Synchronises exactly one chat, exactly as far as it is allowed to.
 *
 * <p>Three invariants drive the design:
 * <ul>
 *   <li>only ACTIVE (or PAUSED, for revision cleanup) allowlisted chats are ever read;</li>
 *   <li>never outside the approved date range or the retention floor;</li>
 *   <li>ingestion is idempotent, so a crash mid-run costs at most repeated work.</li>
 * </ul>
 */
@Service
public class ChatSyncService {

    private static final Logger log = LoggerFactory.getLogger(ChatSyncService.class);

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

    public ChatSyncService(ChatRepository chatRepository,
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

    public SyncOutcome sync(UUID tenantId, UUID chatId) {
        Instant now = this.clock.instant();
        Optional<ChatActivation> maybeActivation = this.activationRepository.find(tenantId, chatId);
        if (maybeActivation.isEmpty()) {
            return SyncOutcome.skipped("No activation record");
        }
        ChatActivation activation = maybeActivation.get();

        // A chat that is not (yet) allowed to hold content must not hold content.
        if (!activation.state().mayRetainContent()) {
            int purged = this.messageRepository.deleteForChat(tenantId, chatId);
            if (purged > 0) {
                this.auditService.system(tenantId, AuditAction.RETENTION_PURGE, chatId, AuditService.ALLOWED,
                        Map.of("reason", "chat is " + activation.state(), "messagesDeleted", purged));
            }
            return SyncOutcome.skipped("Chat is " + activation.state());
        }

        Optional<Chat> maybeChat = this.chatRepository.find(tenantId, chatId);
        if (maybeChat.isEmpty()) {
            return SyncOutcome.skipped("Chat row missing");
        }
        Chat chat = maybeChat.get();

        Optional<TelegramConnection> maybeConnection =
                this.connectionRepository.find(tenantId, chat.connectionId());
        if (maybeConnection.isEmpty() || !maybeConnection.get().ready()) {
            return SyncOutcome.skipped("Telegram connection is not ready");
        }
        ConnectionRef ref = maybeConnection.get().ref();

        SyncCheckpoint checkpoint = this.checkpointRepository.findOrCreate(tenantId, chatId);
        if (!checkpoint.dueAt(now)) {
            return SyncOutcome.skipped("Backoff window has not elapsed");
        }
        this.checkpointRepository.recordAttempt(tenantId, chatId, now);

        try {
            SyncOutcome membershipOutcome = refreshMembership(activation, chat, ref);
            if (membershipOutcome != null) {
                return membershipOutcome;
            }

            ConsentCoverage coverage = this.consentService.coverageFor(activation);
            if (!coverage.complete() && activation.state() == ActivationState.ACTIVE) {
                pause(activation, "Consent coverage incomplete: " + coverage.gaps().size() + " participant(s)");
                return new SyncOutcome(SyncOutcome.Status.PAUSED_CONSENT, 0, 0,
                        "Paused pending consent");
            }

            Instant windowStart = activation.effectiveFrom(now);
            Instant windowEnd = activation.approvedTo().isBefore(now) ? activation.approvedTo() : now;
            if (!windowEnd.isAfter(windowStart)) {
                return SyncOutcome.skipped("Approved window has closed");
            }

            int ingested = 0;
            ingested += pullForward(tenantId, chat, ref, checkpoint, windowStart, windowEnd);
            ingested += backfill(tenantId, chat, ref, windowStart, windowEnd);
            int revisions = applyRevisions(tenantId, chat, ref, checkpoint, now);

            SyncCheckpoint refreshed = this.checkpointRepository.findOrCreate(tenantId, chatId);
            this.checkpointRepository.recordSuccess(tenantId, chatId,
                    refreshed.newestSyncedMessageId(), refreshed.oldestSyncedMessageId(),
                    refreshed.syncedFrom(), refreshed.syncedTo(), refreshed.backfillComplete(), now);

            this.auditService.system(tenantId, AuditAction.SYNC_SUCCEEDED, chatId, AuditService.ALLOWED,
                    Map.of("messagesIngested", ingested, "revisionsApplied", revisions));
            return new SyncOutcome(SyncOutcome.Status.SYNCED, ingested, revisions, "ok");
        }
        catch (TelegramFloodWaitException ex) {
            // Telegram told us exactly how long to wait. Honour it rather than backing off blindly.
            Instant nextAttempt = now.plus(ex.retryAfter());
            this.checkpointRepository.recordFailure(tenantId, chatId, ex.code(),
                    "Flood wait " + ex.retryAfter().toSeconds() + "s", nextAttempt, false, now);
            this.auditService.system(tenantId, AuditAction.SYNC_FLOOD_WAIT, chatId, AuditService.ERROR,
                    Map.of("retryAfterSeconds", ex.retryAfter().toSeconds()));
            return new SyncOutcome(SyncOutcome.Status.FLOOD_WAIT, 0, 0,
                    "Flood wait " + ex.retryAfter().toSeconds() + "s");
        }
        catch (TelegramGatewayException ex) {
            return handleGatewayFailure(tenantId, chatId, checkpoint, ex, now);
        }
        catch (RuntimeException ex) {
            log.error("Unexpected sync failure for chat {}", chatId, ex);
            Duration delay = Backoff.delay(checkpoint.consecutiveFailures() + 1,
                    this.properties.sync().initialBackoff(), this.properties.sync().maxBackoff());
            this.checkpointRepository.recordFailure(tenantId, chatId, "INTERNAL",
                    ex.getClass().getSimpleName(), now.plus(delay), false, now);
            this.auditService.system(tenantId, AuditAction.SYNC_FAILED, chatId, AuditService.ERROR,
                    Map.of("errorCode", "INTERNAL"));
            return new SyncOutcome(SyncOutcome.Status.RETRYABLE_FAILURE, 0, 0, "Internal error");
        }
    }

    private SyncOutcome handleGatewayFailure(UUID tenantId, UUID chatId, SyncCheckpoint checkpoint,
                                             TelegramGatewayException ex, Instant now) {
        int attempt = checkpoint.consecutiveFailures() + 1;
        // Authentication and permission failures are not retried: no amount of waiting
        // turns a revoked session or a lost membership back into a readable chat.
        boolean halt = !ex.retryable() || attempt >= this.properties.sync().maxConsecutiveFailures();
        Instant nextAttempt = halt ? null
                : now.plus(Backoff.delay(attempt, this.properties.sync().initialBackoff(),
                        this.properties.sync().maxBackoff()));

        this.checkpointRepository.recordFailure(tenantId, chatId, ex.code(), ex.getMessage(),
                nextAttempt, halt, now);
        this.auditService.system(tenantId, halt ? AuditAction.SYNC_HALTED : AuditAction.SYNC_FAILED,
                chatId, AuditService.ERROR,
                Map.of("errorCode", ex.code(), "attempt", attempt, "retryable", ex.retryable()));
        return new SyncOutcome(halt ? SyncOutcome.Status.HALTED : SyncOutcome.Status.RETRYABLE_FAILURE,
                0, 0, ex.code());
    }

    /**
     * Refreshes the participant list and forces renewed review when the membership changed:
     * the set of people whose consent is required is no longer the set that consented.
     */
    private SyncOutcome refreshMembership(ChatActivation activation, Chat chat, ConnectionRef ref) {
        List<RemoteParticipant> remote = this.gateway.listParticipants(ref, chat.tgChatId());
        if (remote.isEmpty()) {
            return null;
        }
        for (RemoteParticipant participant : remote) {
            this.participantRepository.upsert(new ChatParticipant(Ids.newId(), chat.tenantId(), chat.id(),
                    participant.tgUserId(), participant.displayName(), participant.bot(), null, null));
        }
        this.participantRepository.markLeft(chat.tenantId(), chat.id(),
                remote.stream().map(RemoteParticipant::tgUserId).toList());

        String fingerprint = this.participantRepository.membershipFingerprint(chat.tenantId(), chat.id());
        if (activation.membershipFingerprint() == null) {
            this.activationRepository.updateMembershipFingerprint(chat.tenantId(), chat.id(), fingerprint);
            return null;
        }
        if (!fingerprint.equals(activation.membershipFingerprint())) {
            this.activationRepository.updateMembershipFingerprint(chat.tenantId(), chat.id(), fingerprint);
            pause(activation, "Chat membership changed; consent coverage must be reviewed");
            this.auditService.system(chat.tenantId(), AuditAction.MEMBERSHIP_CHANGED, chat.id(),
                    AuditService.DENIED, Map.of("action", "paused pending renewed consent review"));
            return new SyncOutcome(SyncOutcome.Status.PAUSED_MEMBERSHIP_CHANGED, 0, 0,
                    "Membership changed");
        }
        return null;
    }

    private void pause(ChatActivation activation, String reason) {
        this.activationRepository.updateState(activation.tenantId(), activation.chatId(),
                ActivationState.PAUSED, reason);
        this.auditService.system(activation.tenantId(), AuditAction.CHAT_PAUSED, activation.chatId(),
                AuditService.DENIED, Map.of("reason", reason));
    }

    /** Incremental catch-up: everything newer than the newest id we already hold. */
    private int pullForward(UUID tenantId, Chat chat, ConnectionRef ref, SyncCheckpoint checkpoint,
                            Instant windowStart, Instant windowEnd) {
        int ingested = 0;
        Long cursor = checkpoint.newestSyncedMessageId();
        for (int page = 0; page < this.properties.sync().maxPagesPerRun(); page++) {
            MessageWindow window = MessageWindow.forward(windowStart, windowEnd, cursor,
                    this.properties.sync().pageSize());
            RemoteMessagePage remotePage = this.gateway.fetchMessages(ref, chat.tgChatId(), window);
            if (remotePage.messages().isEmpty()) {
                break;
            }
            ingested += store(tenantId, chat, remotePage.messages());
            cursor = remotePage.messages().get(remotePage.messages().size() - 1).messageId();
            if (!remotePage.hasMore()) {
                break;
            }
        }
        return ingested;
    }

    /** Bounded backwards backfill, stopping at the approved/retention floor. */
    private int backfill(UUID tenantId, Chat chat, ConnectionRef ref, Instant windowStart, Instant windowEnd) {
        SyncCheckpoint checkpoint = this.checkpointRepository.findOrCreate(tenantId, chat.id());
        if (checkpoint.backfillComplete()) {
            return 0;
        }
        int ingested = 0;
        Long cursor = checkpoint.oldestSyncedMessageId();
        boolean complete = false;
        for (int page = 0; page < this.properties.sync().maxPagesPerRun(); page++) {
            MessageWindow window = MessageWindow.backward(windowStart, windowEnd, cursor,
                    this.properties.sync().pageSize());
            RemoteMessagePage remotePage = this.gateway.fetchMessages(ref, chat.tgChatId(), window);
            if (remotePage.messages().isEmpty()) {
                complete = true;
                break;
            }
            ingested += store(tenantId, chat, remotePage.messages());
            cursor = remotePage.messages().get(0).messageId();
            if (!remotePage.hasMore()) {
                complete = true;
                break;
            }
        }
        if (complete) {
            this.checkpointRepository.recordSuccess(tenantId, chat.id(),
                    checkpoint.newestSyncedMessageId(), cursor, windowStart, windowEnd, true,
                    this.clock.instant());
        }
        return ingested;
    }

    /** Applies upstream edits and deletions to rows we already hold. */
    private int applyRevisions(UUID tenantId, Chat chat, ConnectionRef ref, SyncCheckpoint checkpoint,
                               Instant now) {
        Instant since = checkpoint.lastSuccessAt() == null
                ? now.minus(Duration.ofDays(1)) : checkpoint.lastSuccessAt();
        List<RemoteMessage> revisions = this.gateway.fetchRevisions(ref, chat.tgChatId(), since,
                this.properties.sync().pageSize());
        int applied = 0;
        for (RemoteMessage revision : revisions) {
            if (revision.deleted()) {
                applied += this.messageRepository.markDeleted(tenantId, chat.id(), revision.messageId(), now);
            }
            else {
                store(tenantId, chat, List.of(revision));
                applied++;
            }
        }
        return applied;
    }

    private int store(UUID tenantId, Chat chat, List<RemoteMessage> messages) {
        RemoteChat descriptor = new RemoteChat(chat.tgChatId(), chat.title(), chat.chatType(),
                chat.username(), chat.memberCount());
        int stored = 0;
        Long newest = null;
        Long oldest = null;
        Instant earliest = null;
        Instant latest = null;

        for (RemoteMessage message : messages) {
            if (message.deleted()) {
                this.messageRepository.markDeleted(tenantId, chat.id(), message.messageId(),
                        this.clock.instant());
                continue;
            }
            String link = this.gateway.messageLink(descriptor, message.messageId()).orElse(null);
            this.messageRepository.upsert(new StoredMessage(Ids.newId(), tenantId, chat.id(),
                    message.messageId(), message.senderTgUserId(), message.senderDisplayName(),
                    message.sentAt(), message.editedAt(), null, message.text() == null ? "" : message.text(),
                    message.replyToMessageId(), link));
            stored++;
            newest = (newest == null || message.messageId() > newest) ? message.messageId() : newest;
            oldest = (oldest == null || message.messageId() < oldest) ? message.messageId() : oldest;
            earliest = (earliest == null || message.sentAt().isBefore(earliest)) ? message.sentAt() : earliest;
            latest = (latest == null || message.sentAt().isAfter(latest)) ? message.sentAt() : latest;
        }
        if (stored > 0) {
            this.checkpointRepository.recordSuccess(tenantId, chat.id(), newest, oldest, earliest, latest,
                    this.checkpointRepository.findOrCreate(tenantId, chat.id()).backfillComplete(),
                    this.clock.instant());
        }
        return stored;
    }
}
