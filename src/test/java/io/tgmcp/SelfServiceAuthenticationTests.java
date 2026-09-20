package io.tgmcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.SortedMap;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.tenancy.AppUser;
import io.tgmcp.tenancy.AppUserRepository;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.TestPropertySource;
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
 * Users change frequently, so an operator should not have to create every account by hand: an
 * email/password sign-up form, and a redirect-based "Sign in with Telegram" that provisions its
 * own account on first use. Once "Sign in with Telegram" is configured, neither path alone is
 * sufficient any more: registration also links a Telegram identity before the account is
 * created at all, and every sign-in (password or widget) ends at a mandatory Telegram-delivered
 * code before a session actually exists. See {@link io.tgmcp.tenancy.SelfRegistrationService},
 * {@link io.tgmcp.ui.TelegramLoginController} and {@link io.tgmcp.security.LoginTwoFactorService}.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "tgmcp.telegram-login.bot-token=123456789:test-bot-token-not-a-real-secret",
        "tgmcp.telegram-login.bot-username=TestConsentBot"
})
class SelfServiceAuthenticationTests extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "123456789:test-bot-token-not-a-real-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private io.tgmcp.telegram.TelegramConnectionRepository connections;

    @Test
    void selfRegistrationLinksTelegramVerifiesACodeAndThenSignsIn() throws Exception {
        MvcResult registered = this.mockMvc.perform(post("/register").with(csrf())
                        .param("workspaceName", "Orbital Widgets")
                        .param("email", "new-owner@example.invalid")
                        .param("password", "a-strong-password")
                        .param("passwordConfirm", "a-strong-password"))
                .andExpect(redirectedUrl("/register/link-telegram"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) registered.getRequest().getSession(false);
        assertThat(session).isNotNull();
        // Nothing is created until the Telegram step actually completes.
        assertThat(this.users.findByEmail("new-owner@example.invalid")).isEmpty();

        long telegramUserId = 777_001L;
        this.mockMvc.perform(get("/login/telegram/callback").session(session)
                        .params(signedTelegramParams(telegramUserId, "Grace", "Hopper", "grace")))
                .andExpect(redirectedUrl("/login/verify"));

        AppUser created = this.users.findByEmail("new-owner@example.invalid").orElseThrow();
        assertThat(created.role()).isEqualTo("OWNER");
        assertThat(created.telegramUserId()).isEqualTo(telegramUserId);
        // A session was never established by the Telegram step alone.
        assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .isNull();

        String code = this.telegramBotMessenger.latestCodeFor(telegramUserId);
        this.mockMvc.perform(post("/login/verify").session(session).with(csrf())
                        .param("code", code))
                .andExpect(redirectedUrl("/"));

        SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context).isNotNull();
        assertThat(context.getAuthentication().getName()).isEqualTo(created.email());

        // A fresh tenant, with no chats -- distinct from whatever other tenants exist.
        Long tenantCount = this.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tenants WHERE id = ?", Long.class, created.tenantId());
        assertThat(tenantCount).isEqualTo(1L);
    }

    @Test
    void aWrongCodeIsRejectedAndDoesNotSignIn() throws Exception {
        MvcResult registered = this.mockMvc.perform(post("/register").with(csrf())
                        .param("workspaceName", "Wrong Code Co")
                        .param("email", "wrong-code@example.invalid")
                        .param("password", "a-strong-password")
                        .param("passwordConfirm", "a-strong-password"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) registered.getRequest().getSession(false);

        long telegramUserId = 777_002L;
        this.mockMvc.perform(get("/login/telegram/callback").session(session)
                .params(signedTelegramParams(telegramUserId, "Wrong", "Code", "wrongcode")));

        this.mockMvc.perform(post("/login/verify").session(session).with(csrf())
                        .param("code", "000000"))
                .andExpect(redirectedUrl("/login/verify"))
                .andExpect(flash().attributeExists("error"));
        assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .isNull();
    }

    @Test
    void duplicateEmailIsRejectedWithoutCreatingASecondAccount() throws Exception {
        this.fixtures.signInReadyTeam("existing", "taken@example.invalid",
                "{noop}whatever-hash");

        this.mockMvc.perform(post("/register").with(csrf())
                        .param("workspaceName", "Another Team")
                        .param("email", "taken@example.invalid")
                        .param("password", "a-strong-password")
                        .param("passwordConfirm", "a-strong-password"))
                .andExpect(redirectedUrl("/register"))
                .andExpect(flash().attributeExists("error"));

        Long accountsWithThatEmail = this.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM app_users WHERE lower(email) = 'taken@example.invalid'", Long.class);
        assertThat(accountsWithThatEmail).isEqualTo(1L);
    }

    @Test
    void tooShortAPasswordIsRejected() throws Exception {
        this.mockMvc.perform(post("/register").with(csrf())
                        .param("workspaceName", "Team")
                        .param("email", "short-pw@example.invalid")
                        .param("password", "short")
                        .param("passwordConfirm", "short"))
                .andExpect(redirectedUrl("/register"))
                .andExpect(flash().attributeExists("error"));

        assertThat(this.users.findByEmail("short-pw@example.invalid")).isEmpty();
    }

    @Test
    void mismatchedConfirmationIsRejected() throws Exception {
        this.mockMvc.perform(post("/register").with(csrf())
                        .param("workspaceName", "Team")
                        .param("email", "mismatch@example.invalid")
                        .param("password", "a-strong-password")
                        .param("passwordConfirm", "a-different-password"))
                .andExpect(redirectedUrl("/register"))
                .andExpect(flash().attributeExists("error"));

        assertThat(this.users.findByEmail("mismatch@example.invalid")).isEmpty();
    }

    @Test
    void telegramSignInSendsACodeThenProvisionsAndStartsAQrConnection() throws Exception {
        long telegramUserId = 555_001L;
        // A brand-new Telegram identity: the widget alone only proves identity, so this lands
        // on the code-entry step rather than an authenticated session.
        MvcResult first = this.mockMvc.perform(get("/login/telegram/callback")
                        .params(signedTelegramParams(telegramUserId, "Ada", "Lovelace", "ada")))
                .andExpect(redirectedUrl("/login/verify"))
                .andReturn();
        AppUser provisioned = this.users.findByTelegramUserId(telegramUserId).orElseThrow();
        assertThat(provisioned.role()).isEqualTo("OWNER");
        assertThat(provisioned.enabled()).isTrue();

        MockHttpSession session = (MockHttpSession) first.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .isNull();

        // Entering the code is what actually establishes the session AND (for this brand-new
        // signup only) starts the QR handshake, rather than an empty dashboard with a
        // Connection tab still to find.
        String code = this.telegramBotMessenger.latestCodeFor(telegramUserId);
        this.mockMvc.perform(post("/login/verify").session(session).with(csrf())
                        .param("code", code))
                .andExpect(redirectedUrl("/connect"));

        SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context).isNotNull();
        assertThat(context.getAuthentication().getName()).isEqualTo(provisioned.email());

        this.mockMvc.perform(get("/connect").session(session)).andExpect(status().isOk());
        io.tgmcp.telegram.TelegramConnection connection = this.connections
                .findByOwner(provisioned.tenantId(), provisioned.id()).orElseThrow();
        assertThat(connection.status()).isEqualTo(io.tgmcp.telegram.gateway.LoginStage.WAIT_QR_SCAN);
    }

    @Test
    void aReturningTelegramIdentityResolvesToTheSameAccountNotASecondOne() throws Exception {
        long telegramUserId = 555_002L;
        this.mockMvc.perform(get("/login/telegram/callback")
                .params(signedTelegramParams(telegramUserId, "Ada", "Lovelace", "ada")));

        // A second widget confirmation for the same identity still only reaches the code step
        // (a returning identity does not auto-start a QR connection again), never a second row.
        this.mockMvc.perform(get("/login/telegram/callback")
                        .params(signedTelegramParams(telegramUserId, "Ada", "Lovelace", "ada")))
                .andExpect(redirectedUrl("/login/verify"));

        Long accountsForThisTelegramId = this.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM app_users WHERE telegram_user_id = ?", Long.class, telegramUserId);
        assertThat(accountsForThisTelegramId).isEqualTo(1L);
    }

    @Test
    void aCorrectPasswordAloneNoLongerSignsInAnAccountWithTelegramLinked() throws Exception {
        MvcResult registered = this.mockMvc.perform(post("/register").with(csrf())
                        .param("workspaceName", "Two Factor Co")
                        .param("email", "two-factor@example.invalid")
                        .param("password", "a-strong-password")
                        .param("passwordConfirm", "a-strong-password"))
                .andReturn();
        MockHttpSession registrationSession = (MockHttpSession) registered.getRequest().getSession(false);
        long telegramUserId = 555_003L;
        this.mockMvc.perform(get("/login/telegram/callback").session(registrationSession)
                .params(signedTelegramParams(telegramUserId, "Marie", "Curie", "marie")));
        this.telegramBotMessenger.reset();

        // A correct password check alone (a fresh session, nothing carried over from
        // registration) lands on the code step, not an authenticated session.
        MvcResult passwordAttempt = this.mockMvc.perform(post("/login").with(csrf())
                        .param("username", "two-factor@example.invalid")
                        .param("password", "a-strong-password"))
                .andExpect(redirectedUrl("/login/verify"))
                .andReturn();
        MockHttpSession loginSession = (MockHttpSession) passwordAttempt.getRequest().getSession(false);
        assertThat(loginSession).isNotNull();
        assertThat(loginSession.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .isNull();

        String code = this.telegramBotMessenger.latestCodeFor(telegramUserId);
        this.mockMvc.perform(post("/login/verify").session(loginSession).with(csrf())
                        .param("code", code))
                .andExpect(redirectedUrl("/"));
        SecurityContext context = (SecurityContext) loginSession.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context).isNotNull();
        assertThat(context.getAuthentication().getName()).isEqualTo("two-factor@example.invalid");
    }

    @Test
    void whenTelegramBotCannotDeliverTheCodeSignInFailsRatherThanSkippingTheSecondFactor() throws Exception {
        long telegramUserId = 555_004L;
        this.telegramBotMessenger.blockChat(telegramUserId);

        this.mockMvc.perform(get("/login/telegram/callback")
                        .params(signedTelegramParams(telegramUserId, "Rejected", "Delivery", "blocked")))
                .andExpect(redirectedUrl("/login?telegramCodeError"));
    }

    @Test
    void telegramSignInRejectsATamperedHash() throws Exception {
        org.springframework.util.MultiValueMap<String, String> params = signedTelegramParams(555_005L, "Eve", "",
                "eve");
        params.set("first_name", "Mallory"); // Changed after signing -- the hash no longer matches.

        this.mockMvc.perform(get("/login/telegram/callback").params(params))
                .andExpect(redirectedUrl("/login?telegramError"));

        assertThat(this.users.findByTelegramUserId(555_005L)).isEmpty();
    }

    @Test
    void telegramSignInRejectsAStaleAuthDate() throws Exception {
        long telegramUserId = 555_006L;
        long staleAuthDate = this.clock.instant().minus(Duration.ofHours(2)).getEpochSecond();

        SortedMap<String, String> fields = new TreeMap<>();
        fields.put("id", String.valueOf(telegramUserId));
        fields.put("first_name", "Stale");
        fields.put("username", "stale");
        fields.put("auth_date", String.valueOf(staleAuthDate));
        String hash = telegramHash(fields);

        org.springframework.util.LinkedMultiValueMap<String, String> params = new org.springframework.util
                .LinkedMultiValueMap<>();
        fields.forEach(params::add);
        params.add("hash", hash);

        this.mockMvc.perform(get("/login/telegram/callback").params(params))
                .andExpect(redirectedUrl("/login?telegramError"));

        assertThat(this.users.findByTelegramUserId(telegramUserId)).isEmpty();
    }

    private org.springframework.util.LinkedMultiValueMap<String, String> signedTelegramParams(long telegramUserId,
            String firstName, String lastName, String username) {
        SortedMap<String, String> fields = new TreeMap<>();
        fields.put("id", String.valueOf(telegramUserId));
        fields.put("first_name", firstName);
        if (lastName != null && !lastName.isBlank()) {
            fields.put("last_name", lastName);
        }
        fields.put("username", username);
        fields.put("auth_date", String.valueOf(this.clock.instant().getEpochSecond()));
        String hash = telegramHash(fields);

        org.springframework.util.LinkedMultiValueMap<String, String> params = new org.springframework.util
                .LinkedMultiValueMap<>();
        fields.forEach(params::add);
        params.add("hash", hash);
        return params;
    }

    /** Mirrors TelegramLoginController#verify's data-check-string and HMAC construction exactly. */
    private static String telegramHash(SortedMap<String, String> fields) {
        StringBuilder dataCheckString = new StringBuilder();
        for (var entry : fields.entrySet()) {
            if (!dataCheckString.isEmpty()) {
                dataCheckString.append('\n');
            }
            dataCheckString.append(entry.getKey()).append('=').append(entry.getValue());
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] secretKey = sha256.digest(BOT_TOKEN.getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
            byte[] computed = mac.doFinal(dataCheckString.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(computed);
        }
        catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
