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
import io.tgmcp.telegram.gateway.RemoteMembership;
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

    /**
     * Entry point for an operator explicitly asking to sync right now (the "Sync now" button).
     * A halted checkpoint (see {@link #handleGatewayFailure}) deliberately stops the scheduler
     * from silently hammering Telegram after what looked like a permanent failure -- but that
     * also means, unless something clears it, ingestion stays stuck forever with no way back
     * even once whatever caused it is gone (a since-fixed bug, a since-restored permission, or
     * simply a TDLib hiccup misclassified as permanent). An operator explicitly asking to sync
     * is exactly the human attention a halt exists to wait for, so this clears it first.
     */
    public SyncOutcome syncNow(UUID tenantId, UUID chatId) {
        this.checkpointRepository.clearHalt(tenantId, chatId);
        return sync(tenantId, chatId);
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

            // Security-audit finding F06: PAUSED.mayRetainContent()==true (so the gate above
            // does not purge it), but "may keep what it already has" is not "may keep fetching
            // more" -- a chat is commonly paused precisely because consent or membership is
            // not currently trustworthy, and a manually triggered sync (the UI's "Sync now")
            // reached this same method with no separate check of its own. Only an ACTIVE chat
            // with verified-complete coverage (just confirmed above) may ingest new content;
            // every other retainable-but-not-active state stops here.
            if (activation.state() != ActivationState.ACTIVE) {
                return SyncOutcome.skipped("Chat is " + activation.state() + "; not fetching new content");
            }

            Instant windowStart = activation.effectiveFrom(now);
            Instant windowEnd = activation.approvedTo().isBefore(now) ? activation.approvedTo() : now;
            if (!windowEnd.isAfter(windowStart)) {
                return SyncOutcome.skipped("Approved window has closed");
            }

            int ingested = 0;
            try {
                ingested += pullForward(tenantId, chat, ref, checkpoint, windowStart, windowEnd, activation.epoch(),
                        activation.contextRevision());
                ingested += backfill(tenantId, chat, ref, windowStart, windowEnd, activation.epoch(),
                        activation.contextRevision());
                int revisions = applyRevisions(tenantId, chat, ref, checkpoint, now, activation.epoch(),
                        activation.contextRevision());

                SyncCheckpoint refreshed = this.checkpointRepository.findOrCreate(tenantId, chatId);
                this.checkpointRepository.recordSuccess(tenantId, chatId,
                        refreshed.newestSyncedMessageId(), refreshed.oldestSyncedMessageId(),
                        refreshed.syncedFrom(), refreshed.syncedTo(), refreshed.backfillComplete(), now);

                this.auditService.system(tenantId, AuditAction.SYNC_SUCCEEDED, chatId, AuditService.ALLOWED,
                        Map.of("messagesIngested", ingested, "revisionsApplied", revisions));
                return new SyncOutcome(SyncOutcome.Status.SYNCED, ingested, revisions, "ok");
            }
            catch (ConcurrentRevocationException ex) {
                // Security-audit finding F07: access was revoked (and, in the ABA case, possibly
                // re-proposed under a fresh epoch) while this run already held content fetched
                // from Telegram but not yet committed. requireNotFenced() below caught it before
                // that content reached the database, so nothing revoked-against was stored.
                this.auditService.system(tenantId, AuditAction.SYNC_HALTED, chatId, AuditService.DENIED,
                        Map.of("reason", "activation changed during sync (revoked or superseded)",
                                "messagesIngestedBeforeAbort", ingested));
                return new SyncOutcome(SyncOutcome.Status.HALTED, ingested, 0,
                        "Activation changed during sync; aborted before committing further content");
            }
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
        // The audit trail above is only visible from the management UI's /audit page; this is
        // the same event logged server-side too, since a halt otherwise stops ingestion
        // silently -- forever, until an operator happens to look -- with no trace in Railway's
        // own logs to explain why. ex.getMessage() never carries raw upstream TDLib text (see
        // TdlibErrorTranslationTests), so this is safe to log as-is.
        if (halt) {
            log.warn("Sync halted for chat {}: errorCode={} message={} (after {} attempt(s))",
                    chatId, ex.code(), ex.getMessage(), attempt);
        }
        return new SyncOutcome(halt ? SyncOutcome.Status.HALTED : SyncOutcome.Status.RETRYABLE_FAILURE,
                0, 0, ex.code());
    }

    /**
     * Refreshes the participant list and forces renewed review when the membership changed:
     * the set of people whose consent is required is no longer the set that consented.
     *
     * <p>A broadcast channel's "membership" is its subscriber list, which is not who this app
     * asks consent from (see {@link io.tgmcp.consent.ConsentCoverage#channelBroadcast}) and
     * routinely churns for reasons that have nothing to do with consent -- tracking it here
     * would either pause a channel on every subscriber count change or (once Telegram allows
     * seeing the list at all) require an unbounded consent list from people who never posted
     * anything. Skipped entirely for that chat type.
     */
    private SyncOutcome refreshMembership(ChatActivation activation, Chat chat, ConnectionRef ref) {
        if ("CHANNEL".equals(chat.chatType())) {
            return null;
        }
        RemoteMembership membership = this.gateway.listParticipants(ref, chat.tgChatId());
        List<RemoteParticipant> remote = membership.participants();
        if (remote.isEmpty() || !membership.complete()) {
            // Security-audit finding F04 (empty case) and SEC-02 (incomplete case, e.g. a
            // capped supergroup enumeration): a chat that has never had a verified membership
            // (membershipFingerprint == null) simply cannot reach ACTIVE at all -- coverageFor
            // treats zero required participants as "unknown", never "complete" -- so returning
            // early here without upserting or marking anyone departed is expected and harmless
            // for that case. But once a chat *has* had a real, verified membership, an empty or
            // incomplete response (a transient API glitch, a lost permission, a >5000-member
            // safety cap) must never be treated as "nothing changed": upserting only the
            // partial set understates who needs to consent, and markLeft against that same
            // partial set would wrongly report real, still-present members as having departed.
            // Pause for review instead of silently narrowing coverage.
            if (activation.membershipFingerprint() != null) {
                String reason = remote.isEmpty()
                        ? "Membership list came back empty; consent coverage cannot be verified"
                        : "Membership enumeration was incomplete; consent coverage cannot be verified";
                pause(activation, reason);
                this.auditService.system(chat.tenantId(), AuditAction.MEMBERSHIP_CHANGED, chat.id(),
                        AuditService.DENIED, Map.of("action",
                                "paused: " + (remote.isEmpty() ? "empty" : "incomplete") + " membership response"));
                return new SyncOutcome(SyncOutcome.Status.PAUSED_MEMBERSHIP_CHANGED, 0, 0,
                        "Membership could not be verified");
            }
            return null;
        }
        for (RemoteParticipant participant : remote) {
            this.participantRepository.upsert(new ChatParticipant(Ids.newId(), chat.tenantId(), chat.id(),
                    participant.tgUserId(), participant.displayName(), participant.bot(), null, null));
        }
        this.participantRepository.markLeft(chat.tenantId(), chat.id(),
                remote.stream().map(RemoteParticipant::tgUserId).toList());
        purgeMessagesFromUnconsentedAuthors(activation, chat);

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

    /**
     * Security-audit finding F05 (departures) broadened by SEC-03: message storage is never
     * gated on a per-sender consent check at the point of ingestion alone -- once a message
     * with a given sender is stored, it does not automatically disappear just because that
     * sender's own consent later stops being valid. F05 only re-checked a participant at the
     * instant {@code markLeft} reported them as newly departed, so a still-active member whose
     * consent record simply expired, or a departed member whose consent record expires only
     * *after* they left, could keep their stored messages indefinitely. Every known
     * participant -- active or departed -- is reconciled against current consent on every
     * successful membership refresh instead, so a lapse is never missed for lack of a
     * departure event to hang it on.
     */
    private void purgeMessagesFromUnconsentedAuthors(ChatActivation activation, Chat chat) {
        for (ChatParticipant participant : this.participantRepository.findAll(chat.tenantId(), chat.id())) {
            if (participant.bot()) {
                continue;
            }
            if (this.consentService.hasValidConsent(chat.tenantId(), chat.id(), participant.tgUserId(),
                    activation.contextRevision())) {
                continue;
            }
            int deleted = this.messageRepository.deleteForSender(chat.tenantId(), chat.id(),
                    participant.tgUserId());
            if (deleted > 0) {
                this.auditService.system(chat.tenantId(), AuditAction.DATA_DELETED, chat.id(),
                        AuditService.ALLOWED,
                        Map.of("reason", "participant has no valid consent for the current context",
                                "participantTgUserId", participant.tgUserId(), "messagesDeleted", deleted,
                                "stillActive", participant.active()));
            }
        }
    }

    private void pause(ChatActivation activation, String reason) {
        this.activationRepository.updateState(activation.tenantId(), activation.chatId(),
                ActivationState.PAUSED, reason);
        this.auditService.system(activation.tenantId(), AuditAction.CHAT_PAUSED, activation.chatId(),
                AuditService.DENIED, Map.of("reason", reason));
    }

    /**
     * Incremental catch-up: everything newer than the newest id we already hold.
     *
     * <p>Security-audit finding F12: a page with zero matched messages no longer means "done" --
     * a source whose native pagination only walks backward (TDLib) can return an empty filtered
     * page while there is still older, in-window content further back (a backlog of messages
     * newer than the approved window sits "in the way"). Only {@code hasMore() == false} means
     * done; an empty page with {@code hasMore() == true} instead advances the resume cursor and
     * keeps going, so a large newer-than-window backlog can no longer strand catch-up above the
     * messages it actually needs.
     */
    private int pullForward(UUID tenantId, Chat chat, ConnectionRef ref, SyncCheckpoint checkpoint,
                            Instant windowStart, Instant windowEnd, long epoch, String contextRevision) {
        int ingested = 0;
        Long afterId = checkpoint.newestSyncedMessageId();
        Long resumeFrom = null;
        for (int page = 0; page < this.properties.sync().maxPagesPerRun(); page++) {
            MessageWindow window = MessageWindow.forward(windowStart, windowEnd, afterId, resumeFrom,
                    this.properties.sync().pageSize());
            RemoteMessagePage remotePage = this.gateway.fetchMessages(ref, chat.tgChatId(), window);
            if (!remotePage.messages().isEmpty()) {
                requireNotFenced(tenantId, chat.id(), epoch);
                ingested += store(tenantId, chat, remotePage.messages(), contextRevision);
                afterId = remotePage.messages().get(remotePage.messages().size() - 1).messageId();
            }
            resumeFrom = remotePage.resumeCursor();
            if (!remotePage.hasMore()) {
                break;
            }
        }
        return ingested;
    }

    /** Bounded backwards backfill, stopping at the approved/retention floor. */
    private int backfill(UUID tenantId, Chat chat, ConnectionRef ref, Instant windowStart, Instant windowEnd,
                         long epoch, String contextRevision) {
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
            if (!remotePage.messages().isEmpty()) {
                requireNotFenced(tenantId, chat.id(), epoch);
                ingested += store(tenantId, chat, remotePage.messages(), contextRevision);
                cursor = remotePage.messages().get(0).messageId();
            }
            if (remotePage.resumeCursor() != null) {
                // The gateway's own raw traversal position is at or before the oldest matched
                // id, so it always resumes at least as far back as deriving from messages()
                // alone would -- see the pullForward javadoc above for why messages() can be
                // empty here at all.
                cursor = remotePage.resumeCursor();
            }
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
                               Instant now, long epoch, String contextRevision) {
        Instant since = checkpoint.lastSuccessAt() == null
                ? now.minus(Duration.ofDays(1)) : checkpoint.lastSuccessAt();
        List<RemoteMessage> revisions = this.gateway.fetchRevisions(ref, chat.tgChatId(), since,
                this.properties.sync().pageSize());
        int applied = 0;
        for (RemoteMessage revision : revisions) {
            requireNotFenced(tenantId, chat.id(), epoch);
            if (revision.deleted()) {
                applied += this.messageRepository.markDeleted(tenantId, chat.id(), revision.messageId(), now);
            }
            else {
                store(tenantId, chat, List.of(revision), contextRevision);
                applied++;
            }
        }
        return applied;
    }

    /**
     * Security-audit finding F07: re-checked immediately before every content commit inside a
     * sync run, so a revoke (or a revoke-then-reactivate cycle, which only a fencing epoch and
     * not a plain state check can catch) that lands while this run is mid-fetch stops it before
     * any further content lands, rather than only being noticed on the next scheduled run.
     */
    private void requireNotFenced(UUID tenantId, UUID chatId, long epoch) {
        Optional<ChatActivation> current = this.activationRepository.find(tenantId, chatId);
        if (current.isEmpty() || current.get().state() != ActivationState.ACTIVE
                || current.get().epoch() != epoch) {
            throw new ConcurrentRevocationException();
        }
    }

    private static final class ConcurrentRevocationException extends RuntimeException {
        ConcurrentRevocationException() {
            super(null, null, false, false);
        }
    }

    /**
     * Security-audit finding SEC-03: message storage used to be gated on nothing but the
     * approved date window -- whoever sent a message, storing it was purely a function of
     * when it was sent. A message whose sender has no valid consent for the chat's current
     * processing context -- including a sender this application has never seen in {@code
     * chat_participants} at all, e.g. someone who left before onboarding ever ran -- is
     * quarantined here instead of being written: rejected rather than automatically accepted,
     * as the audit's own remediation text requires for an unknown author.
     */
    private int store(UUID tenantId, Chat chat, List<RemoteMessage> messages, String contextRevision) {
        RemoteChat descriptor = new RemoteChat(chat.tgChatId(), chat.title(), chat.chatType(),
                chat.username(), chat.memberCount());
        int stored = 0;
        int quarantined = 0;
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
            if (!this.consentService.authorEligible(tenantId, chat.id(), message.senderTgUserId(),
                    contextRevision)) {
                quarantined++;
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
        if (quarantined > 0) {
            this.auditService.system(tenantId, AuditAction.MESSAGE_QUARANTINED, chat.id(), AuditService.DENIED,
                    Map.of("messagesSkipped", quarantined,
                            "reason", "sender has no valid consent for the current context"));
        }
        return stored;
    }
}
