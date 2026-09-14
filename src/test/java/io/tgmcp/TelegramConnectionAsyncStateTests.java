package io.tgmcp;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import io.tgmcp.telegram.TelegramConnection;
import io.tgmcp.telegram.TelegramConnectionService;
import io.tgmcp.telegram.gateway.LoginMethod;
import io.tgmcp.telegram.gateway.LoginStage;
import io.tgmcp.telegram.gateway.LoginState;
import io.tgmcp.telegram.gateway.TelegramAdapterKind;
import io.tgmcp.telegram.gateway.TelegramGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security-audit finding F13: QR-code login completes out of band -- the user scans on
 * another device, and TDLib reports {@code authorizationStateReady} as a push update with no
 * request of its own to persist it. {@code beginLogin}'s own synchronous return only ever
 * carries whatever state existed at the moment it was called (WAIT_QR_SCAN), so before this
 * fix the database status never advanced to READY and {@code discoverChats} kept reading an
 * empty chat list even after the UI's live status display showed the login had succeeded.
 *
 * <p>Simulates the asynchronous transition by capturing the listener {@link
 * TelegramConnectionService#beginLogin} registers via {@link TelegramGateway#onStateChange}
 * and invoking it directly, well after {@code beginLogin} has already returned -- exactly the
 * ordering a real QR scan produces.
 */
class TelegramConnectionAsyncStateTests extends AbstractIntegrationTest {

    @Autowired
    private TelegramConnectionService connectionService;

    @MockitoSpyBean
    private TelegramGateway gateway;

    private Fixtures.Team team(String slug) {
        return this.fixtures.signInReadyTeam(slug, "owner@" + slug + ".invalid",
                PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("pw"));
    }

    @SuppressWarnings("unchecked")
    private Consumer<LoginState> captureRegisteredListener() {
        AtomicReference<Consumer<LoginState>> captured = new AtomicReference<>();
        Mockito.doAnswer(invocation -> {
            captured.set(invocation.getArgument(1));
            return null;
        }).when(this.gateway).onStateChange(ArgumentMatchers.any(), ArgumentMatchers.any());
        return state -> captured.get().accept(state);
    }

    @Test
    void anAsynchronousReadyTransitionIsPersistedWithIdentityAndNoFurtherSubmitIsNeeded() {
        Mockito.when(this.gateway.kind()).thenReturn(TelegramAdapterKind.TDLIB);
        Fixtures.Team team = team("async-ready");
        Consumer<LoginState> deliverToListener = captureRegisteredListener();

        this.connectionService.beginLogin(team.actor(), LoginMethod.QR_CODE, null);
        assertThat(this.connectionService.find(team.actor()).orElseThrow().ready())
                .as("beginLogin's own synchronous return only ever reflects the pre-scan state")
                .isFalse();

        // TDLib reporting the QR scan completed, asynchronously, after beginLogin returned --
        // now carrying real account identity (TdlibTelegramGateway.withAccountIdentity).
        deliverToListener.accept(new LoginState(LoginStage.READY, null, 555L, "real_owner", "login.tdlib.ready"));

        TelegramConnection persisted = this.connectionService.find(team.actor()).orElseThrow();
        assertThat(persisted.ready())
                .as("the DB status discoverChats() actually reads must now show READY")
                .isTrue();
        assertThat(persisted.tgUserId()).isEqualTo(555L);
        assertThat(persisted.tgUsername()).isEqualTo("real_owner");
    }

    @Test
    void anAsynchronousLogoutTransitionIsAlsoPersisted() {
        Mockito.when(this.gateway.kind()).thenReturn(TelegramAdapterKind.TDLIB);
        Fixtures.Team team = team("async-logout");
        Consumer<LoginState> deliverToListener = captureRegisteredListener();

        this.connectionService.beginLogin(team.actor(), LoginMethod.QR_CODE, null);
        deliverToListener.accept(new LoginState(LoginStage.READY, null, 555L, "real_owner", "login.tdlib.ready"));
        assertThat(this.connectionService.find(team.actor()).orElseThrow().ready()).isTrue();

        // TDLib reporting the session was logged out server-side, with no submit* call of
        // its own -- the audit's "error/logout ham yozilsin" requirement.
        deliverToListener.accept(LoginState.of(LoginStage.DISCONNECTED, "login.tdlib.disconnected"));

        assertThat(this.connectionService.find(team.actor()).orElseThrow().ready()).isFalse();
    }
}
