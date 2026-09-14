package io.tgmcp.sync;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.tgmcp.chat.ActivationState;
import io.tgmcp.chat.ChatActivation;
import io.tgmcp.chat.ChatActivationRepository;
import io.tgmcp.config.AppProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link ChatSyncService} across the allowlisted chats with bounded concurrency.
 *
 * <p>Disabled in tests ({@code tgmcp.sync.scheduled=false}) so that sync behaviour is
 * asserted by calling the service directly rather than by waiting on a timer.
 */
@Component
@ConditionalOnProperty(name = "tgmcp.sync.scheduled", havingValue = "true", matchIfMissing = true)
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final ChatActivationRepository activationRepository;
    private final ChatSyncService syncService;
    private final ExecutorService executor;

    public SyncScheduler(ChatActivationRepository activationRepository,
                         ChatSyncService syncService,
                         AppProperties properties) {
        this.activationRepository = activationRepository;
        this.syncService = syncService;
        this.executor = Executors.newFixedThreadPool(
                Math.max(1, properties.sync().maxConcurrentChats()),
                Thread.ofPlatform().name("chat-sync-", 0).daemon().factory());
    }

    @Scheduled(fixedDelayString = "${tgmcp.sync.interval:PT30S}")
    public void run() {
        List<ChatActivation> due = this.activationRepository.findAllByState(ActivationState.ACTIVE);
        List<Future<SyncOutcome>> futures = due.stream()
                .map(activation -> this.executor.submit(
                        () -> this.syncService.sync(activation.tenantId(), activation.chatId())))
                .toList();
        for (Future<SyncOutcome> future : futures) {
            try {
                future.get(2, TimeUnit.MINUTES);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            catch (Exception ex) {
                log.warn("Sync task failed: {}", ex.getClass().getSimpleName());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        this.executor.shutdownNow();
    }
}
