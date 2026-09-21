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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
                "login.tdlib.ready"));
        // The mock's own in-memory session map still says WAIT_QR_SCAN (nothing ever drove it
        // through submitCode/submitPassword) -- exactly like a real TDLib ref no one has
        // reclaimed since a restart, this makes status() fall back to the persisted stage the
        // listener above just wrote, instead of the stale live one.
        stubCurrentStateToThrowNotCurrentOwner();

        this.mockMvc.perform(get("/login/finish").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        assertThat(this.users.findByPhoneNumber(phoneNumber).orElseThrow().telegramUserId()).isEqualTo(777L);
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
                newPhoneNumber, "login.tdlib.ready"));
        stubCurrentStateToThrowNotCurrentOwner();

        this.mockMvc.perform(get("/login/finish").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"))
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.notNullValue()));

        assertThat(this.tenants.findById(newTenantId))
                .as("the abandoned duplicate tenant must be rolled back, not left half-registered")
                .isEmpty();
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
