package io.tgmcp.sync;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import io.tgmcp.audit.AuditAction;
import io.tgmcp.audit.AuditService;
import io.tgmcp.chat.ChatActivation;
import io.tgmcp.chat.ChatActivationRepository;
import io.tgmcp.message.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies retention to stored messages.
 *
 * <p>Retention is enforced on the stored rows themselves. Because search is a PostgreSQL
 * generated {@code tsvector} column on the same row and there is no cache or vector index in
 * this MVP, deleting the row removes the message from the index and from every derived
 * read path in the same statement — there is no second copy to forget about.
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final ChatActivationRepository activationRepository;
    private final MessageRepository messageRepository;
    private final AuditService auditService;
    private final Clock clock;

    public RetentionService(ChatActivationRepository activationRepository,
                            MessageRepository messageRepository,
                            AuditService auditService,
                            Clock clock) {
        this.activationRepository = activationRepository;
        this.messageRepository = messageRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${tgmcp.sync.retention-interval:PT1H}")
    @ConditionalOnProperty(name = "tgmcp.sync.scheduled", havingValue = "true", matchIfMissing = true)
    public void runAll() {
        for (ChatActivation activation : this.activationRepository.findAll()) {
            try {
                apply(activation);
            }
            catch (RuntimeException ex) {
                log.warn("Retention pass failed for chat {}: {}", activation.chatId(),
                        ex.getClass().getSimpleName());
            }
        }
    }

    /**
     * Deletes anything outside the approved range or older than the retention period.
     * Idempotent: running it twice deletes nothing the second time.
     */
    @Transactional
    public int apply(ChatActivation activation) {
        Instant now = this.clock.instant();
        if (!activation.state().mayRetainContent()) {
            return purgeAll(activation.tenantId(), activation.chatId(), "state=" + activation.state());
        }
        Instant keepFrom = activation.effectiveFrom(now);
        Instant keepTo = activation.approvedTo();
        int deleted = this.messageRepository.deleteOutsideWindow(activation.tenantId(), activation.chatId(),
                keepFrom, keepTo);
        if (deleted > 0) {
            this.auditService.system(activation.tenantId(), AuditAction.RETENTION_PURGE, activation.chatId(),
                    AuditService.ALLOWED,
                    Map.of("messagesDeleted", deleted,
                            "retentionDays", activation.retentionDays(),
                            "keepFrom", keepFrom.toString(),
                            "keepTo", keepTo.toString()));
        }
        return deleted;
    }

    @Transactional
    public int purgeAll(UUID tenantId, UUID chatId, String reason) {
        int deleted = this.messageRepository.deleteForChat(tenantId, chatId);
        if (deleted > 0) {
            this.auditService.system(tenantId, AuditAction.DATA_DELETED, chatId, AuditService.ALLOWED,
                    Map.of("messagesDeleted", deleted, "reason", reason));
        }
        return deleted;
    }

    public Duration retentionOf(ChatActivation activation) {
        return Duration.ofDays(activation.retentionDays());
    }
}
