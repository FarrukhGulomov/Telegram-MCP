package io.tgmcp;

import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import io.tgmcp.telegram.TelegramConnectionService;
import io.tgmcp.telegram.gateway.LoginMethod;
import io.tgmcp.telegram.gateway.LoginStage;
import io.tgmcp.telegram.gateway.TelegramAdapterKind;
import io.tgmcp.telegram.gateway.TelegramGateway;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every user account gets its own, fully isolated Telegram connection: this replaces the
 * previous single-shared-TDLib-session design (security-audit finding SEC-01), where {@link
 * io.tgmcp.telegram.tdlib.TdlibTelegramGateway} was one native TDLib client shared by the whole
 * process and {@link TelegramConnectionService} enforced a hard single-connection guard for the
 * TDLIB adapter -- rejecting a second tenant's login outright while any other tenant's connection
 * was active.
 *
 * <p>{@link io.tgmcp.telegram.tdlib.RoutedTdlibTelegramGateway} now gives each tenant its own
 * native TDLib client (and its own on-disk session directory) instead, so that guard has been
 * removed entirely. These tests assert the new invariant it left behind: two different tenants
 * can each independently connect at the same time, with no cross-tenant interference, whether or
 * not the configured adapter reports itself as TDLIB -- simulated here by spying the gateway
 * bean's {@code kind()}, since this environment has no real TDLib binary to exercise
 * {@code RoutedTdlibTelegramGateway} itself against.
 */
class TelegramConnectionIsolationTests extends AbstractIntegrationTest {

    @Autowired
    private TelegramConnectionService connectionService;

    /** Spied only to make kind() report TDLIB; every other call still runs the real mock logic. */
    @MockitoSpyBean
    private TelegramGateway gateway;

    private Fixtures.Team team(String slug) {
        return this.fixtures.signInReadyTeam(slug, "owner@" + slug + ".invalid",
                PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("pw"));
    }

    @Test
    void twoTenantsCanBothBeginLoginAtTheSameTimeUnderTdlib() {
        Mockito.when(this.gateway.kind()).thenReturn(TelegramAdapterKind.TDLIB);
        Fixtures.Team teamA = team("isolation-a");
        Fixtures.Team teamB = team("isolation-b");

        assertThat(this.connectionService.beginLogin(teamA.actor(), LoginMethod.QR_CODE, null)).isNotNull();
        assertThat(this.connectionService.beginLogin(teamB.actor(), LoginMethod.QR_CODE, null)).isNotNull();
    }

    @Test
    void disconnectingOneTenantDoesNotAffectAnothersActiveConnection() {
        Mockito.when(this.gateway.kind()).thenReturn(TelegramAdapterKind.TDLIB);
        Fixtures.Team teamA = team("isolation-c");
        Fixtures.Team teamB = team("isolation-d");

        this.connectionService.beginLogin(teamA.actor(), LoginMethod.QR_CODE, null);
        this.connectionService.beginLogin(teamB.actor(), LoginMethod.QR_CODE, null);

        this.connectionService.disconnect(teamA.actor());

        assertThat(this.connectionService.find(teamA.actor()).orElseThrow().status())
                .isEqualTo(LoginStage.DISCONNECTED);
        assertThat(this.connectionService.find(teamB.actor()).orElseThrow().status())
                .isNotEqualTo(LoginStage.DISCONNECTED);
    }

    @Test
    void theMockAdapterAlsoIsolatesTenantsFromOneAnother() {
        Fixtures.Team teamA = team("isolation-e");
        Fixtures.Team teamB = team("isolation-f");

        assertThat(this.connectionService.beginLogin(teamA.actor(), LoginMethod.QR_CODE, null)).isNotNull();
        assertThat(this.connectionService.beginLogin(teamB.actor(), LoginMethod.QR_CODE, null)).isNotNull();
    }

    /**
     * Regression guard for the removed SEC-01 guard: two truly concurrent {@code beginLogin}
     * calls from different tenants (not just issued one after the other, as the tests above do)
     * must both succeed -- neither may be rejected as though it were contending for a shared
     * session that no longer exists.
     */
    @Test
    void trulyConcurrentBeginLoginAttemptsFromDifferentTenantsBothSucceed() throws Exception {
        Mockito.when(this.gateway.kind()).thenReturn(TelegramAdapterKind.TDLIB);
        Fixtures.Team teamA = team("isolation-g");
        Fixtures.Team teamB = team("isolation-h");

        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<Boolean> resultA = executor.submit(() -> {
                barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                try {
                    this.connectionService.beginLogin(teamA.actor(), LoginMethod.QR_CODE, null);
                    return true;
                }
                catch (IllegalStateException ex) {
                    return false;
                }
            });
            java.util.concurrent.Future<Boolean> resultB = executor.submit(() -> {
                barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                try {
                    this.connectionService.beginLogin(teamB.actor(), LoginMethod.QR_CODE, null);
                    return true;
                }
                catch (IllegalStateException ex) {
                    return false;
                }
            });

            boolean aSucceeded = resultA.get(10, java.util.concurrent.TimeUnit.SECONDS);
            boolean bSucceeded = resultB.get(10, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(aSucceeded)
                    .as("a different tenant's concurrent login must never be rejected as contending "
                            + "for a shared session")
                    .isTrue();
            assertThat(bSucceeded)
                    .as("a different tenant's concurrent login must never be rejected as contending "
                            + "for a shared session")
                    .isTrue();
        }
        finally {
            executor.shutdownNow();
        }
    }
}
