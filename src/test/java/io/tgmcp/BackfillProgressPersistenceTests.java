package io.tgmcp;

import java.time.Instant;
import java.util.List;

import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import io.tgmcp.sync.ChatSyncService;
import io.tgmcp.sync.SyncCheckpointRepository;
import io.tgmcp.telegram.gateway.ConnectionRef;
import io.tgmcp.telegram.gateway.RemoteMessage;
import io.tgmcp.telegram.gateway.RemoteMessagePage;
import io.tgmcp.telegram.gateway.TelegramGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * External review finding: {@code ChatSyncService#backfill} used to persist its advanced
 * cursor only once the whole backfill finished within one run ({@code complete}). Whenever a
 * fetched raw page stored nothing at all -- every message in it already deleted upstream, or
 * quarantined for lacking consent -- {@code store()}'s own incremental checkpoint update (which
 * covers the common case where something in the page is genuinely stored) never fires either,
 * so a run that hit its page budget without reaching a real stopping point silently discarded
 * the only record of how far it had actually walked. The very next run started over from the
 * same, never-advanced checkpoint and re-fetched the identical page forever.
 *
 * <p>Runs against a one-page-per-run budget so a single raw page reliably exhausts it, and stubs
 * the gateway directly (bypassing the mock adapter's own filtering) so the fetched page can be
 * entirely {@code deleted()} messages -- the one case that never advances the checkpoint on its
 * own via {@code store()}.
 */
@TestPropertySource(properties = "tgmcp.sync.max-pages-per-run=1")
class BackfillProgressPersistenceTests extends AbstractIntegrationTest {

    @Autowired
    private ChatSyncService syncService;

    @Autowired
    private SyncCheckpointRepository checkpoints;

    @MockitoSpyBean
    private TelegramGateway gateway;

    @Test
    void aBackfillPageThatStoresNothingStillAdvancesTheCheckpointsCursor() {
        Fixtures.ReadyChat chat = this.fixtures.readyEngineeringChat("backfill-progress", this.clock.instant());

        // pullForward's own first (and, at maxPagesPerRun=1, only) call: nothing to catch up on,
        // so backfill is reached with checkpoint.oldestSyncedMessageId() still null.
        RemoteMessagePage empty = RemoteMessagePage.empty();
        // backfill's own single call, budget-limited to exactly one: a raw page that is entirely
        // messages already deleted upstream -- store() stores none of them, so its own
        // incremental checkpoint update never fires for this page at all.
        RemoteMessagePage allDeletedPage = new RemoteMessagePage(
                // Ascending messageId order, per RemoteMessagePage's own contract -- backfill()
                // takes messages().get(0) as this page's oldest id.
                List.of(
                        new RemoteMessage(499L, null, null, Instant.parse("2026-01-01T00:00:00Z"), null,
                                true, "", null, null, null),
                        new RemoteMessage(500L, null, null, Instant.parse("2026-01-01T00:00:00Z"), null,
                                true, "", null, null, null)),
                true, null);
        Mockito.doReturn(empty).doReturn(allDeletedPage)
                .when(this.gateway).fetchMessages(ArgumentMatchers.any(ConnectionRef.class),
                        ArgumentMatchers.anyLong(), ArgumentMatchers.any());

        this.syncService.sync(chat.team().tenant().id(), chat.chat().id());

        assertThat(this.checkpoints.find(chat.team().tenant().id(), chat.chat().id()).orElseThrow()
                .oldestSyncedMessageId())
                .as("the single fetched (but entirely unstored) page's own cursor must still be "
                        + "persisted, or the next run re-fetches the exact same page forever")
                .isEqualTo(499L);
    }
}
