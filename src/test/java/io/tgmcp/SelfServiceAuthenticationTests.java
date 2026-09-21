package io.tgmcp;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.tgmcp.security.TelegramWebhookSecret;
import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.tenancy.AppUser;
import io.tgmcp.tenancy.AppUserRepository;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
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
 * Users change frequently, so an operator should not have to create every account by hand: a
 * workspace/email sign-up form that links a Telegram identity, and signing in with a one-time
 * code delivered to that same Telegram chat -- there is no password anywhere. Proving control of
 * the linked Telegram account is the whole login. See {@link
 * io.tgmcp.tenancy.SelfRegistrationService}, {@link io.tgmcp.security.TelegramLinkingService} and
 * {@link io.tgmcp.ui.TelegramBotWebhookController}.
 */
@AutoConfigureMockMvc
class SelfServiceAuthenticationTests extends AbstractIntegrationTest {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("data-link-token=\"([^\"]+)\"");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private TelegramWebhookSecret webhookSecret;

    @Test
    void selfRegistrationLinksTelegramViaTheBotThenVerifiesACodeAndSignsIn() throws Exception {
        MvcResult registered = this.mockMvc.perform(post("/register").with(csrf())
                        .param("workspaceName", "Orbital Widgets")
                        .param("email", "new-owner@example.invalid"))
                .andExpect(redirectedUrl("/register/link-telegram"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) registered.getRequest().getSession(false);
        assertThat(session).isNotNull();
        // Nothing is created until the Telegram step actually completes.
        assertThat(this.users.findByEmail("new-owner@example.invalid")).isEmpty();

        String token = fetchLinkToken(session);
        assertThat(fetchLinkStatus(token, session)).isEmpty();

        long telegramUserId = 777_001L;
        sendStartCommand(token, telegramUserId, "grace");

        String redirectTo = fetchLinkStatus(token, session);
        assertThat(redirectTo).isEqualTo("/login/verify");

        AppUser created = this.users.findByEmail("new-owner@example.invalid").orElseThrow();
        assertThat(created.role()).isEqualTo("OWNER");
        assertThat(created.telegramUserId()).isEqualTo(telegramUserId);
        assertThat(created.telegramUsername()).isEqualTo("grace");
        // A session was never established by the linking step alone.
        assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .isNull();

        Long accountCount = this.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM app_users WHERE telegram_user_id = ?", Long.class, telegramUserId);
        assertThat(accountCount).isEqualTo(1L);

        String code = this.telegramBotMessenger.latestCodeFor(telegramUserId);
        this.mockMvc.perform(post("/login/verify").session(session).with(csrf())
                        .param("code", code))
                .andExpect(redirectedUrl("/"));
        SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context).isNotNull();
        assertThat(context.getAuthentication().getName()).isEqualTo(created.email());
    }

    @Test
    void aTelegramIdentityAlreadyLinkedToAnotherAccountCannotLinkASecondOne() throws Exception {
        long telegramUserId = 777_002L;
        registerAndLink("first-owner@example.invalid", telegramUserId, "shared");

        MvcResult registered = this.mockMvc.perform(post("/register").with(csrf())
                        .param("workspaceName", "Second Team")
                        .param("email", "second-owner@example.invalid"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) registered.getRequest().getSession(false);
        String token = fetchLinkToken(session);

        // The webhook update itself is accepted (an unrelated, already-claimed token is simply
        // ignored -- see TelegramLinkTokenRepository#claim), but this NEW token was never
        // claimed by that identity, so the second registration just keeps waiting.
        assertThat(fetchLinkStatus(token, session)).isEmpty();
        assertThat(this.users.findByEmail("second-owner@example.invalid")).isEmpty();
    }

    @Test
    void duplicateEmailIsRejectedWithoutCreatingASecondAccount() throws Exception {
        this.fixtures.signInReadyTeam("existing", "taken@example.invalid");

        this.mockMvc.perform(post("/register").with(csrf())
                        .param("workspaceName", "Another Team")
                        .param("email", "taken@example.invalid"))
                .andExpect(redirectedUrl("/register"))
                .andExpect(flash().attributeExists("error"));

        Long accountsWithThatEmail = this.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM app_users WHERE lower(email) = 'taken@example.invalid'", Long.class);
        assertThat(accountsWithThatEmail).isEqualTo(1L);
    }

    @Test
    void signingInWithATelegramCodeByEmailNeedsNoFurtherConfirmation() throws Exception {
        long telegramUserId = 777_003L;
        registerAndLink("code-login@example.invalid", telegramUserId, "codeuser");
        this.telegramBotMessenger.reset();

        MvcResult sent = this.mockMvc.perform(post("/login").with(csrf())
                        .param("identifier", "code-login@example.invalid"))
                .andExpect(redirectedUrl("/login/verify"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) sent.getRequest().getSession(false);

        String code = this.telegramBotMessenger.latestCodeFor(telegramUserId);
        this.mockMvc.perform(post("/login/verify").session(session).with(csrf())
                        .param("code", code))
                .andExpect(redirectedUrl("/"));
        SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context).isNotNull();
        assertThat(context.getAuthentication().getName()).isEqualTo("code-login@example.invalid");
    }

    @Test
    void signingInWithATelegramCodeByUsernameAlsoWorks() throws Exception {
        long telegramUserId = 777_004L;
        registerAndLink("username-login@example.invalid", telegramUserId, "UsernameLogin");
        this.telegramBotMessenger.reset();

        this.mockMvc.perform(post("/login").with(csrf())
                        .param("identifier", "@usernamelogin"))
                .andExpect(redirectedUrl("/login/verify"));

        assertThat(this.telegramBotMessenger.latestCodeFor(telegramUserId)).isNotBlank();
    }

    @Test
    void signingInWithATelegramCodeForAnUnknownIdentifierGivesNoIndicationEitherWay() throws Exception {
        this.mockMvc.perform(post("/login").with(csrf())
                        .param("identifier", "nobody@example.invalid"))
                .andExpect(redirectedUrl("/login"))
                .andExpect(flash().attribute("message", org.hamcrest.Matchers.notNullValue()));
    }

    @Test
    void aWrongCodeIsRejectedAndDoesNotSignIn() throws Exception {
        long telegramUserId = 777_005L;
        MockHttpSession session = registerAndLink("wrong-code@example.invalid", telegramUserId, "wrongcode");

        this.mockMvc.perform(post("/login/verify").session(session).with(csrf())
                        .param("code", "000000"))
                .andExpect(redirectedUrl("/login/verify"))
                .andExpect(flash().attributeExists("error"));
    }

    /**
     * Registers and links an account, leaving it right where a fresh registration lands: the
     * account exists and a second-factor code is pending in the returned session, but nothing
     * has verified it yet.
     */
    private MockHttpSession registerAndLink(String email, long telegramUserId, String username) throws Exception {
        MvcResult registered = this.mockMvc.perform(post("/register").with(csrf())
                        .param("workspaceName", "Team for " + email)
                        .param("email", email))
                .andReturn();
        MockHttpSession session = (MockHttpSession) registered.getRequest().getSession(false);
        String token = fetchLinkToken(session);
        sendStartCommand(token, telegramUserId, username);
        String redirectTo = fetchLinkStatus(token, session);
        assertThat(redirectTo).isEqualTo("/login/verify");
        return session;
    }

    private String fetchLinkToken(MockHttpSession session) throws Exception {
        MvcResult page = this.mockMvc.perform(get("/register/link-telegram").session(session))
                .andExpect(status().isOk())
                .andReturn();
        Matcher matcher = TOKEN_PATTERN.matcher(page.getResponse().getContentAsString());
        assertThat(matcher.find()).as("link token present in the rendered page").isTrue();
        return matcher.group(1);
    }

    private String fetchLinkStatus(String token, MockHttpSession session) throws Exception {
        MvcResult result = this.mockMvc.perform(get("/register/link-telegram/status")
                        .session(session)
                        .param("token", token))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        Matcher matcher = Pattern.compile("\"redirectTo\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        assertThat(matcher.find()).as("well-formed status response").isTrue();
        return matcher.group(1);
    }

    private void sendStartCommand(String token, long telegramUserId, String username) throws Exception {
        String body = """
                {"update_id":1,"message":{"message_id":1,"date":0,\
                "from":{"id":%d,"is_bot":false,"first_name":"Test","username":"%s"},\
                "chat":{"id":%d,"type":"private"},"text":"/start %s"}}
                """.formatted(telegramUserId, username, telegramUserId, token);
        this.mockMvc.perform(post("/telegram/bot-webhook")
                        .header("X-Telegram-Bot-Api-Secret-Token", this.webhookSecret.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }
}
