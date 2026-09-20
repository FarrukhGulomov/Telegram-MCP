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

    /**
     * Real-account testing hit this directly: a container restart silently restores an
     * already-authorized TDLib session from its persisted local database (an async READY
     * transition with no beginLogin call of its own -- exactly what this test simulates), but
     * a visitor re-submitting the login form has no way to know that. Before this fix,
     * beginLogin() forwarded straight to TDLib, which correctly refused the redundant
     * setAuthenticationPhoneNumber call with a confusing protocol-level error.
     */
    @Test
    void beginLoginOnAnAlreadyReadyConnectionReturnsReadyInsteadOfErroring() {
        Mockito.when(this.gateway.kind()).thenReturn(TelegramAdapterKind.TDLIB);
        Fixtures.Team team = team("already-ready");
        Consumer<LoginState> deliverToListener = captureRegisteredListener();
        this.connectionService.beginLogin(team.actor(), LoginMethod.QR_CODE, null);
        deliverToListener.accept(new LoginState(LoginStage.READY, null, 555L, "real_owner", "login.tdlib.ready"));

        LoginState state = this.connectionService.beginLogin(team.actor(), LoginMethod.PHONE_NUMBER, "+1555");

        assertThat(state.stage()).isEqualTo(LoginStage.READY);
        Mockito.verify(this.gateway, Mockito.never())
                .beginLogin(ArgumentMatchers.any(), Mockito.eq(LoginMethod.PHONE_NUMBER), ArgumentMatchers.any());
    }

    /**
     * The same restart scenario as {@link #beginLoginOnAnAlreadyReadyConnectionReturnsReadyInsteadOfErroring},
     * but for {@code status()} -- the call every plain {@code GET /connect} page view makes, not
     * only a login resubmission. Confirmed against a real production incident: after a restart,
     * {@code gateway.currentState(ref)} throws {@code TelegramAuthException} ("not the current
     * owner") for a ref no one has reclaimed yet, and status() must not let that propagate for an
     * account the database already knows is connected.
     */
    @Test
    void statusOnAnAlreadyReadyConnectionReturnsReadyEvenIfNothingHasReclaimedOwnershipYet() {
        Mockito.when(this.gateway.kind()).thenReturn(TelegramAdapterKind.TDLIB);
        Fixtures.Team team = team("status-after-restart");
        Consumer<LoginState> deliverToListener = captureRegisteredListener();
        this.connectionService.beginLogin(team.actor(), LoginMethod.QR_CODE, null);
        deliverToListener.accept(new LoginState(LoginStage.READY, null, 555L, "real_owner", "login.tdlib.ready"));
        stubCurrentStateToThrowNotCurrentOwner();

        LoginState state = this.connectionService.status(team.actor());

        assertThat(state.stage()).isEqualTo(LoginStage.READY);
    }

    /**
     * The same restart-resilience gap as above, but for a connection that never reached READY at
     * all -- the actual shape of the reported incident: a tenant whose only prior visit to
     * {@code /connect} created a plain {@code DISCONNECTED} row (via {@code getOrCreate}, before
     * any login was ever attempted), which is enough on its own to make {@code find(actor)}
     * return a row and route through {@code gateway.currentState(ref)}. The fix must fall back to
     * the persisted stage for every stage, not only READY, or this exact non-READY case still
     * throws.
     */
    @Test
    void statusOnAConnectionThatNeverReachedReadyFallsBackToItsPersistedStageInsteadOfErroring() {
        Mockito.when(this.gateway.kind()).thenReturn(TelegramAdapterKind.TDLIB);
        Fixtures.Team team = team("status-never-ready-after-restart");
        this.connectionService.getOrCreate(team.actor());
        stubCurrentStateToThrowNotCurrentOwner();

        LoginState state = this.connectionService.status(team.actor());

        assertThat(state.stage()).isEqualTo(LoginStage.DISCONNECTED);
    }

    /**
     * Confirmed from live production logs: disconnect() had the exact same restart-resilience
     * gap status() did -- gateway.disconnect(ref) threw "not the current owner" for a ref no
     * one had reclaimed since the last restart, aborting the whole disconnect (including the
     * local DB cleanup) for a real, previously-connected account the operator most needs to be
     * able to sign out of. Verifies the fix's call order rather than a thrown-and-recovered
     * exception, since MockTelegramGateway's own disconnect() never enforces ownership at all
     * (only the real TDLib adapter does) -- what matters here is that TelegramConnectionService
     * always reclaims first.
     */
    @Test
    void disconnectReclaimsOwnershipFirstSoItWorksEvenIfNothingHasReclaimedItSinceARestart() {
        Mockito.when(this.gateway.kind()).thenReturn(TelegramAdapterKind.TDLIB);
        Fixtures.Team team = team("disconnect-after-restart");
        Consumer<LoginState> deliverToListener = captureRegisteredListener();
        this.connectionService.beginLogin(team.actor(), LoginMethod.QR_CODE, null);
        deliverToListener.accept(new LoginState(LoginStage.READY, null, 555L, "real_owner", "login.tdlib.ready"));
        Mockito.clearInvocations(this.gateway);

        this.connectionService.disconnect(team.actor());

        org.mockito.InOrder order = Mockito.inOrder(this.gateway);
        order.verify(this.gateway).onStateChange(ArgumentMatchers.any(), ArgumentMatchers.any());
        order.verify(this.gateway).disconnect(ArgumentMatchers.any());
        assertThat(this.connectionService.find(team.actor()).orElseThrow().ready()).isFalse();
    }

    /**
     * The security boundary the reclaim must not cross: SEC-01 exists specifically so a tenant
     * who merely visited /connect (a still-DISCONNECTED row, no real login ever attempted) can
     * never disconnect whatever real account a different, legitimate tenant is using. Since
     * reclaiming is unconditional once called, it must only ever be called for a connection
     * whose own persisted row proves a real login happened.
     */
    @Test
    void disconnectDoesNotReclaimOwnershipForAConnectionThatNeverEngagedInALogin() {
        Mockito.when(this.gateway.kind()).thenReturn(TelegramAdapterKind.TDLIB);
        Fixtures.Team team = team("disconnect-never-connected");
        this.connectionService.getOrCreate(team.actor());

        this.connectionService.disconnect(team.actor());

        Mockito.verify(this.gateway, Mockito.never()).onStateChange(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    /**
     * Confirmed from live production logs, a third call site with the exact same restart gap
     * status()/disconnect() already closed: {@code ChatActivationService.importParticipants}
     * (reached simply by opening a chat's page) called {@code gateway.listParticipants}, which
     * has no reactive fallback of its own and threw straight through to an unhandled UI error.
     * Rather than adding yet another per-call-site patch, {@link
     * TelegramConnectionService#reclaimOwnershipOnStartup} reclaims once for every persisted
     * READY connection, so every gateway method works again from the very first request after a
     * restart. Verifies it registers a real, actored listener (not a no-op) by delivering an
     * async state through it afterward and checking the DB actually updates -- a no-op listener
     * would silently drop that transition and regress the SEC-06 protection {@link
     * #anAsynchronousLogoutTransitionIsAlsoPersisted} covers for the normal login path.
     */
    @Test
    void reclaimOwnershipOnStartupRegistersARealListenerForEveryReadyConnection() {
        Mockito.when(this.gateway.kind()).thenReturn(TelegramAdapterKind.TDLIB);
        Fixtures.Team team = team("startup-reclaim");
        Consumer<LoginState> deliverToListener = captureRegisteredListener();
        this.connectionService.beginLogin(team.actor(), LoginMethod.QR_CODE, null);
        deliverToListener.accept(new LoginState(LoginStage.READY, null, 555L, "real_owner", "login.tdlib.ready"));
        Mockito.clearInvocations(this.gateway);
        Consumer<LoginState> deliverAfterRestart = captureRegisteredListener();

        this.connectionService.reclaimOwnershipOnStartup();

        Mockito.verify(this.gateway).onStateChange(ArgumentMatchers.any(), ArgumentMatchers.any());
        deliverAfterRestart.accept(LoginState.of(LoginStage.DISCONNECTED, "login.tdlib.disconnected"));
        assertThat(this.connectionService.find(team.actor()).orElseThrow().ready()).isFalse();
    }

    /**
     * Confirmed from live production logs: after {@link #reclaimOwnershipOnStartupRegistersARealListenerForEveryReadyConnection}
     * registers its listener at boot, TDLib also pushes {@code authorizationStateWaitTdlibParameters}
     * through that same listener as a normal, transient part of every fresh process's
     * initialization -- mapped to {@code LoginStage.DISCONNECTED} with message
     * {@code "login.tdlib.initialising"} purely to drive UI copy during a live login flow (see
     * {@code TdlibTelegramGateway.mapAuthorizationState}). Before this fix, persist() treated
     * that exactly like a genuine authorization loss and paused every active chat on every
     * restart, even though the account was never actually logged out -- the real cause behind
     * previously-activated chats silently disappearing from sync after a redeploy.
     */
    @Test
    void reclaimedListenerDoesNotPauseChatsForTheTransientTdlibInitialisingState() {
        Mockito.when(this.gateway.kind()).thenReturn(TelegramAdapterKind.TDLIB);
        Fixtures.Team team = team("startup-reclaim-transient-init");
        Consumer<LoginState> deliverToListener = captureRegisteredListener();
        this.connectionService.beginLogin(team.actor(), LoginMethod.QR_CODE, null);
        deliverToListener.accept(new LoginState(LoginStage.READY, null, 555L, "real_owner", "login.tdlib.ready"));
        Mockito.clearInvocations(this.gateway);
        Consumer<LoginState> deliverAfterRestart = captureRegisteredListener();

        this.connectionService.reclaimOwnershipOnStartup();
        deliverAfterRestart.accept(LoginState.of(LoginStage.DISCONNECTED, "login.tdlib.initialising"));

        TelegramConnection persisted = this.connectionService.find(team.actor()).orElseThrow();
        assertThat(persisted.ready())
                .as("a transient re-init push must not be mistaken for a genuine authorization loss")
                .isFalse();
        assertThat(persisted.status())
                .as("the persisted stage still records the transient state, it just must not trigger a pause")
                .isEqualTo(LoginStage.DISCONNECTED);
    }

    /** A connection that never reached READY has nothing to reclaim -- no live session survives. */
    @Test
    void reclaimOwnershipOnStartupSkipsAConnectionThatNeverReachedReady() {
        Mockito.when(this.gateway.kind()).thenReturn(TelegramAdapterKind.TDLIB);
        Fixtures.Team team = team("startup-reclaim-never-ready");
        this.connectionService.getOrCreate(team.actor());

        this.connectionService.reclaimOwnershipOnStartup();

        Mockito.verify(this.gateway, Mockito.never()).onStateChange(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    /**
     * Simulates the in-memory ownership claim being gone (a fresh process after a restart)
     * without touching whatever is persisted -- exactly what {@code TdlibTelegramGateway}'s
     * volatile, never-persisted {@code currentOwner} field looks like right after a restart.
     * {@code doThrow(...).when(spy)...} rather than {@code when(spy...).thenThrow(...)}: {@code
     * this.gateway} is a spy over the real {@code MockTelegramGateway}, and the latter form
     * calls the real method with the matcher's null placeholder argument before the stub is even
     * registered.
     */
    private void stubCurrentStateToThrowNotCurrentOwner() {
        Mockito.doThrow(new io.tgmcp.telegram.gateway.TelegramAuthException(
                        "This connection is not the current owner of the shared Telegram session"))
                .when(this.gateway).currentState(ArgumentMatchers.any());
    }
}
