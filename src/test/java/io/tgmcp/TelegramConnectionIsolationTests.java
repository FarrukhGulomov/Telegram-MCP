package io.tgmcp;

import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import io.tgmcp.telegram.TelegramConnectionService;
import io.tgmcp.telegram.gateway.LoginMethod;
import io.tgmcp.telegram.gateway.TelegramAdapterKind;
import io.tgmcp.telegram.gateway.TelegramGateway;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Security-audit finding F01: {@link io.tgmcp.telegram.tdlib.TdlibTelegramGateway} is one
 * native TDLib client shared by the whole process -- its {@code ConnectionRef} parameters are
 * accepted but never used to key any state. Two different owners logging in "at the same time"
 * under that adapter would silently share one real Telegram identity: the second owner's
 * beginLogin would re-authenticate the same native session out from under the first.
 *
 * <p>Until a real per-owner native session manager exists, {@link TelegramConnectionService}
 * enforces a hard single-connection guard for the TDLIB adapter: no second owner may start (or
 * continue) a login while another owner's connection is anything but DISCONNECTED. The mock
 * adapter has no shared native state, so the guard only applies when the gateway reports
 * {@link TelegramAdapterKind#TDLIB} -- simulated here by spying the gateway bean, since this
 * environment has no real TDLib binary to exercise the adapter itself against.
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
    void secondOwnerCannotBeginLoginWhileAnotherOwnersConnectionIsActiveUnderTdlib() {
        Mockito.when(this.gateway.kind()).thenReturn(TelegramAdapterKind.TDLIB);
        Fixtures.Team teamA = team("isolation-a");
        Fixtures.Team teamB = team("isolation-b");

        this.connectionService.beginLogin(teamA.actor(), LoginMethod.QR_CODE, null);

        assertThatThrownBy(() -> this.connectionService.beginLogin(teamB.actor(), LoginMethod.QR_CODE, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("validation.singleConnectionOnly");
    }

    @Test
    void secondOwnerCanBeginLoginOnceTheFirstDisconnects() {
        Mockito.when(this.gateway.kind()).thenReturn(TelegramAdapterKind.TDLIB);
        Fixtures.Team teamA = team("isolation-c");
        Fixtures.Team teamB = team("isolation-d");

        this.connectionService.beginLogin(teamA.actor(), LoginMethod.QR_CODE, null);
        this.connectionService.disconnect(teamA.actor());

        assertThat(this.connectionService.beginLogin(teamB.actor(), LoginMethod.QR_CODE, null)).isNotNull();
    }

    @Test
    void theGuardDoesNotApplyToTheMockAdapter() {
        Fixtures.Team teamA = team("isolation-e");
        Fixtures.Team teamB = team("isolation-f");

        this.connectionService.beginLogin(teamA.actor(), LoginMethod.QR_CODE, null);

        assertThat(this.connectionService.beginLogin(teamB.actor(), LoginMethod.QR_CODE, null)).isNotNull();
    }
}
