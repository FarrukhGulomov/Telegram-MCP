package io.tgmcp;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import io.tgmcp.chat.ActivationState;
import io.tgmcp.chat.Chat;
import io.tgmcp.chat.ChatActivation;
import io.tgmcp.chat.ChatActivationRepository;
import io.tgmcp.security.PreAuthLoginService;
import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import io.tgmcp.tenancy.AppUserRepository;
import io.tgmcp.tenancy.TenantRepository;
import io.tgmcp.telegram.TelegramConnection;
import io.tgmcp.telegram.TelegramConnectionRepository;
import io.tgmcp.telegram.gateway.LoginMethod;
import io.tgmcp.telegram.gateway.LoginStage;
import io.tgmcp.telegram.gateway.LoginState;
import io.tgmcp.telegram.gateway.TelegramAdapterKind;
import io.tgmcp.telegram.gateway.TelegramGateway;
import io.tgmcp.telegram.mock.SyntheticDataset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link io.tgmcp.security.PreAuthLoginService}: signing in is the same act as connecting
 * Telegram, so this covers the account-lifecycle decisions that live there rather than in any
 * single controller endpoint -- new-account creation, returning-account reconnection without
 * disturbing existing chats, QR's new-account-only restriction, and the duplicate-identity
 * rollback path a QR signup can hit.
 */
@AutoConfigureMockMvc
class PreAuthLoginFlowTests extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private TenantRepository tenants;
    @Autowired
    private TelegramConnectionRepository connections;
    @Autowired
    private ChatActivationRepository activations;
    @MockitoSpyBean
    private TelegramGateway gateway;

    /**
     * Mirrors what login.js itself does: read the single-use nonce {@code /login/status} hands
     * back once the stage it reports is READY (security-audit finding SEC-R07), so a test can
     * complete the same asynchronous finish path a real QR-scan completion takes.
     */
    private String finishNonceFor(MockHttpSession session) throws Exception {
        String body = this.mockMvc.perform(get("/login/status").session(session))
                .andReturn().getResponse().getContentAsString();
        return (String) JsonMapper.builder().build().readValue(body, java.util.Map.class).get("nonce");
    }

    @Test
    void newPhoneNumberSignsUpAFreshAccountAndReachesReady() throws Exception {
        String phoneNumber = "+19995550001";
        MockHttpSession session = signInViaPhoneNumber(this.mockMvc, phoneNumber);
        assertThat(session).isNotNull();

        this.mockMvc.perform(get("/").session(session)).andExpect(status().isOk());
        assertThat(this.users.findByPhoneNumber(phoneNumber)).isPresent();
    }

    @Test
    void returningAccountReconnectsWithoutPausingItsActiveChats() throws Exception {
        Fixtures.Team team = this.fixtures.team("returning-login");
        Chat chat = this.fixtures.chat(team, SyntheticDataset.ENGINEERING_CHAT, "Returning chat", "returning_chat");
        this.fixtures.participants(team, chat, Fixtures.ENGINEERING_MEMBERS);
        this.fixtures.activation(team, chat, ActivationState.ACTIVE,
                this.clock.instant().minusSeconds(3600), this.clock.instant().plusSeconds(3600), 365);

        UUID originalTenantId = team.tenant().id();
        UUID originalConnectionId = team.connection().id();

        MockHttpSession session = signInViaPhoneNumber(this.mockMvc, team.user().phoneNumber());
        assertThat(session).isNotNull();

        TelegramConnection connection = this.connections.findByOwner(originalTenantId, team.user().id())
                .orElseThrow();
        assertThat(connection.id())
                .as("a routine re-login reuses the same connection row, never creates a new one")
                .isEqualTo(originalConnectionId);
        assertThat(connection.status()).isEqualTo(LoginStage.READY);

        assertThat(this.activations.find(originalTenantId, chat.id()).orElseThrow().state())
                .as("a routine re-login must never pause chats the account already activated")
                .isEqualTo(ActivationState.ACTIVE);
    }

    /**
     * Regression for a real production incident: TDLib's own {@code getMe().phone_number} is
     * bare digits with no "+", and once a real login confirms an account's identity that value
     * overwrites {@code app_users.phone_number} (see {@link
     * PreAuthLoginService#finishIfReady}). Before {@code
     * TdlibTelegramGateway.withAccountIdentity} was fixed to add the "+" back, a returning
     * visitor typing their own number (which {@code normalizePhoneNumber} always renders with a
     * "+") could no longer find their own account and instead collided with it trying to create
     * a brand-new one -- both derive {@code tenants.slug} from the same digits. This simulates
     * that already-broken stored state (as the V10 migration also backfills) and asserts signing
     * in still finds the same account rather than throwing a duplicate-key error.
     */
    @Test
    void signingInFindsAnAccountWhosePhoneNumberWasStoredWithoutALeadingPlus() throws Exception {
        Fixtures.Team team = this.fixtures.team("legacy-no-plus");
        String phoneNumber = team.user().phoneNumber();
        this.jdbcTemplate.update("UPDATE app_users SET phone_number = ? WHERE id = ?",
                phoneNumber.replaceFirst("^\\+", ""), team.user().id());

        MockHttpSession session = signInViaPhoneNumber(this.mockMvc, phoneNumber);
        assertThat(session).isNotNull();

        assertThat(this.users.findByPhoneNumber(phoneNumber))
                .as("the visitor's own, \"+\"-prefixed number must resolve to their existing account")
                .isPresent();
        assertThat(this.tenants.findById(team.tenant().id()))
                .as("no second, colliding tenant should have been created")
                .isPresent();
    }

    @Test
    void qrCodeIsRejectedForAnAlreadyRegisteredPhoneNumber() throws Exception {
        Fixtures.Team team = this.fixtures.team("qr-existing");

        this.mockMvc.perform(post("/login/start").with(csrf())
                        .param("method", LoginMethod.QR_CODE.name())
                        .param("phoneNumber", team.user().phoneNumber()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"))
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.notNullValue()));
    }

    @Test
    void qrCodeCreatesABrandNewAccountWhenThePhoneNumberMatchesNothing() throws Exception {
        Mockito.when(this.gateway.kind()).thenReturn(TelegramAdapterKind.TDLIB);
        Consumer<LoginState> deliverToListener = captureRegisteredListener();

        String phoneNumber = "+19995550099";
        MvcResult started = this.mockMvc.perform(post("/login/start").with(csrf())
                        .param("method", LoginMethod.QR_CODE.name())
                        .param("phoneNumber", phoneNumber))
                .andReturn();
        MockHttpSession session = (MockHttpSession) started.getRequest().getSession(false);
        assertThat(this.users.findByPhoneNumber(phoneNumber))
                .as("a brand-new phone number's tenant+user is created eagerly, before any real login proof")
                .isPresent();

        deliverToListener.accept(new LoginState(LoginStage.READY, null, 777L, "real_scanner", phoneNumber,
                "login.tdlib.ready", null, null));
        // The mock's own in-memory session map still says WAIT_QR_SCAN (nothing ever drove it
        // through submitCode/submitPassword) -- exactly like a real TDLib ref no one has
        // reclaimed since a restart, this makes status() fall back to the persisted stage the
        // listener above just wrote, instead of the stale live one.
        stubCurrentStateToThrowNotCurrentOwner();

        this.mockMvc.perform(post("/login/finish").with(csrf()).session(session)
                        .param("nonce", finishNonceFor(session)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        assertThat(this.users.findByPhoneNumber(phoneNumber).orElseThrow().telegramUserId()).isEqualTo(777L);
    }

    /**
     * Regression: a real user hit a genuinely blank {@code /login} page after a QR scan reached
     * READY on Telegram's side, before the client-side poll in login.js had run {@code
     * /login/finish}. login.html has no visible branch for {@code stage == 'READY'} by design
     * (the normal path never server-renders it -- login.js finishes the sign-in itself via
     * background polling, without a full page load), but a page load can still land here
     * directly: this browser's session still holds the pending login, unconsumed, exactly as it
     * would right after a QR scan confirms and before the next poll tick. Asserts the page shows
     * a real "finishing" message instead of nothing, and still carries {@code
     * data-login-stage="READY"} so login.js (which now treats READY as a stage to resume polling
     * from, not one to ignore) picks the sign-in back up rather than leaving it to time out.
     */
    @Test
    void reloadingLoginWhileReadyButNotYetFinishedShowsAFinishingMessageRatherThanNothing() throws Exception {
        Mockito.when(this.gateway.kind()).thenReturn(TelegramAdapterKind.TDLIB);
        Consumer<LoginState> deliverToListener = captureRegisteredListener();

        String phoneNumber = "+19995550055";
        MvcResult started = this.mockMvc.perform(post("/login/start").with(csrf())
                        .param("method", LoginMethod.QR_CODE.name())
                        .param("phoneNumber", phoneNumber))
                .andReturn();
        MockHttpSession session = (MockHttpSession) started.getRequest().getSession(false);

        deliverToListener.accept(new LoginState(LoginStage.READY, null, 781L, "real_scanner", phoneNumber,
                "login.tdlib.ready", null, null));
        stubCurrentStateToThrowNotCurrentOwner();

        this.mockMvc.perform(get("/login").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-login-stage=\"READY\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Finishing sign-in")));

        // The pending login is still intact (untouched by the reload above), so the same finish
        // round trip login.js would now run on its own still completes it correctly.
        this.mockMvc.perform(post("/login/finish").with(csrf()).session(session)
                        .param("nonce", finishNonceFor(session)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    /**
     * Regression for a real production report: a visitor reloading {@code /login} right after
     * submitting a phone number saw a blank card. Root cause: {@code TdlibTelegramGateway
     * .beginLogin} sends {@code setAuthenticationPhoneNumber} and returns, but the resulting
     * {@code authorizationStateWaitCode} transition is pushed asynchronously (a real round trip
     * to Telegram) -- a page load that lands before that push arrives finds the connection still
     * reporting {@code WAIT_PHONE_NUMBER}, the stage it was in *before* the number was submitted.
     * login.html had no branch for that stage (only the pseudo-stage {@code PHONE_ENTRY} a
     * brand-new, no-pending-login visitor gets), so the card rendered nothing at all. Asserts the
     * page now shows a real hint instead, carrying {@code data-login-stage="WAIT_PHONE_NUMBER"}
     * so login.js (which now polls this stage too) can recover once the real transition lands.
     */
    @Test
    void reloadingLoginWhileStillWaitingForThePhoneNumberToBeConfirmedShowsAHintRatherThanNothing()
            throws Exception {
        Mockito.when(this.gateway.kind()).thenReturn(TelegramAdapterKind.TDLIB);
        LoginState stillWaitingForPhoneNumber = LoginState.of(LoginStage.WAIT_PHONE_NUMBER, "login.tdlib.waitPhone");
        Mockito.doReturn(stillWaitingForPhoneNumber).when(this.gateway)
                .beginLogin(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());

        String phoneNumber = "+19995550033";
        MvcResult started = this.mockMvc.perform(post("/login/start").with(csrf())
                        .param("method", LoginMethod.PHONE_NUMBER.name())
                        .param("phoneNumber", phoneNumber))
                .andReturn();
        MockHttpSession session = (MockHttpSession) started.getRequest().getSession(false);

        // The live gateway (still owned by this same ref) is asked again on the reload below,
        // exactly like beginLogin's own async push had simply not arrived yet.
        Mockito.doReturn(stillWaitingForPhoneNumber).when(this.gateway)
                .currentState(ArgumentMatchers.any());

        this.mockMvc.perform(get("/login").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("data-login-stage=\"WAIT_PHONE_NUMBER\"")))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("Telegram is waiting for a phone number")));
    }

    /**
     * Regression for a real follow-up report: a visitor whose code was sent to a channel they
     * cannot check (the Telegram app on a device they don't have) had no way to learn Telegram
     * itself will use a different, named channel once a resend is actually allowed -- {@code
     * code_info.next_type} and {@code timeout} were discarded exactly like {@code type} was
     * before the delivery-channel fix. Asserts the wait-code screen now spells out both the wait
     * and the channel a resend will switch to.
     */
    @Test
    void waitCodeScreenExplainsWhenAndToWhatChannelAResendWillSwitch() throws Exception {
        Mockito.when(this.gateway.kind()).thenReturn(TelegramAdapterKind.TDLIB);
        LoginState waitingViaTelegramAppWithSmsNext = new LoginState(LoginStage.WAIT_CODE, null, null, null, null,
                "login.tdlib.waitCode.telegramApp", 62, "sms");
        Mockito.doReturn(waitingViaTelegramAppWithSmsNext).when(this.gateway)
                .beginLogin(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());

        MvcResult started = this.mockMvc.perform(post("/login/start").with(csrf())
                        .param("method", LoginMethod.PHONE_NUMBER.name())
                        .param("phoneNumber", "+19995550044"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) started.getRequest().getSession(false);

        // The live gateway is asked again on the GET below, same as every other test here that
        // reloads /login rather than relying on beginLogin's own synchronous response.
        Mockito.doReturn(waitingViaTelegramAppWithSmsNext).when(this.gateway)
                .currentState(ArgumentMatchers.any());

        this.mockMvc.perform(get("/login").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("62")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("SMS")));
    }

    @Test
    void aBrandNewSignupWhoseConfirmedIdentityAlreadyBelongsToAnotherAccountRollsBack() throws Exception {
        Mockito.when(this.gateway.kind()).thenReturn(TelegramAdapterKind.TDLIB);
        Fixtures.Team existing = this.fixtures.team("dup-identity-existing");
        long existingTelegramUserId = existing.user().telegramUserId();

        Consumer<LoginState> deliverToListener = captureRegisteredListener();

        String newPhoneNumber = "+19995550088";
        MvcResult started = this.mockMvc.perform(post("/login/start").with(csrf())
                        .param("method", LoginMethod.QR_CODE.name())
                        .param("phoneNumber", newPhoneNumber))
                .andReturn();
        MockHttpSession session = (MockHttpSession) started.getRequest().getSession(false);
        UUID newTenantId = this.users.findByPhoneNumber(newPhoneNumber).orElseThrow().tenantId();

        // The scanning device turns out to already be a different, existing account.
        deliverToListener.accept(new LoginState(LoginStage.READY, null, existingTelegramUserId, "collides",
                newPhoneNumber, "login.tdlib.ready", null, null));
        stubCurrentStateToThrowNotCurrentOwner();

        this.mockMvc.perform(post("/login/finish").with(csrf()).session(session)
                        .param("nonce", finishNonceFor(session)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"))
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.notNullValue()));

        assertThat(this.tenants.findById(newTenantId))
                .as("the abandoned duplicate tenant must be rolled back, not left half-registered")
                .isEmpty();
    }

    /**
     * Security-audit finding SEC-R07: {@code /login/finish} is a state-changing POST now, not
     * the plain GET a browser prefetcher, a link-preview crawler, or a same-site navigation
     * could trigger with no user action at all.
     */
    @Test
    void loginFinishNoLongerAcceptsAPlainGet() throws Exception {
        this.mockMvc.perform(get("/login/finish"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void loginFinishRefusesAMissingOrIncorrectNonce() throws Exception {
        Mockito.when(this.gateway.kind()).thenReturn(TelegramAdapterKind.TDLIB);
        Consumer<LoginState> deliverToListener = captureRegisteredListener();

        String phoneNumber = "+19995550111";
        MvcResult started = this.mockMvc.perform(post("/login/start").with(csrf())
                        .param("method", LoginMethod.QR_CODE.name())
                        .param("phoneNumber", phoneNumber))
                .andReturn();
        MockHttpSession session = (MockHttpSession) started.getRequest().getSession(false);

        deliverToListener.accept(new LoginState(LoginStage.READY, null, 778L, "real_scanner", phoneNumber,
                "login.tdlib.ready", null, null));
        stubCurrentStateToThrowNotCurrentOwner();

        this.mockMvc.perform(post("/login/finish").with(csrf()).session(session)
                        .param("nonce", "not-the-real-nonce"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        assertThat(this.users.findByPhoneNumber(phoneNumber).orElseThrow().telegramUserId())
                .as("a wrong nonce must not sign anyone in").isNull();
    }

    @Test
    void loginFinishNonceIsSingleUse() throws Exception {
        Mockito.when(this.gateway.kind()).thenReturn(TelegramAdapterKind.TDLIB);
        Consumer<LoginState> deliverToListener = captureRegisteredListener();

        String phoneNumber = "+19995550112";
        MvcResult started = this.mockMvc.perform(post("/login/start").with(csrf())
                        .param("method", LoginMethod.QR_CODE.name())
                        .param("phoneNumber", phoneNumber))
                .andReturn();
        MockHttpSession session = (MockHttpSession) started.getRequest().getSession(false);

        deliverToListener.accept(new LoginState(LoginStage.READY, null, 779L, "real_scanner", phoneNumber,
                "login.tdlib.ready", null, null));
        stubCurrentStateToThrowNotCurrentOwner();
        String nonce = finishNonceFor(session);

        this.mockMvc.perform(post("/login/finish").with(csrf()).session(session).param("nonce", nonce))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        // Replaying the exact same nonce a second time must not work again -- the pending login
        // it belonged to was already cleared by the successful finish above.
        this.mockMvc.perform(post("/login/finish").with(csrf()).session(session).param("nonce", nonce))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void resendCodeReissuesACodeWhileWaitingForOne() throws Exception {
        MvcResult started = this.mockMvc.perform(post("/login/start").with(csrf())
                        .param("method", LoginMethod.PHONE_NUMBER.name())
                        .param("phoneNumber", "+19995550077"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) started.getRequest().getSession(false);

        this.mockMvc.perform(post("/login/code/resend").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("message", org.hamcrest.Matchers.notNullValue()));
    }

    @Test
    void cancellingAnAbandonedNewAccountSignupDeletesTheEagerlyCreatedTenant() throws Exception {
        String phoneNumber = "+19995550066";
        MvcResult started = this.mockMvc.perform(post("/login/start").with(csrf())
                        .param("method", LoginMethod.PHONE_NUMBER.name())
                        .param("phoneNumber", phoneNumber))
                .andReturn();
        MockHttpSession session = (MockHttpSession) started.getRequest().getSession(false);
        UUID tenantId = this.users.findByPhoneNumber(phoneNumber).orElseThrow().tenantId();

        this.mockMvc.perform(post("/login/cancel").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(this.tenants.findById(tenantId)).isEmpty();
        assertThat(this.users.findByPhoneNumber(phoneNumber)).isEmpty();
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

    private void stubCurrentStateToThrowNotCurrentOwner() {
        Mockito.doThrow(new io.tgmcp.telegram.gateway.TelegramAuthException(
                        "This connection is not the current owner of the shared Telegram session"))
                .when(this.gateway).currentState(ArgumentMatchers.any());
    }
}
