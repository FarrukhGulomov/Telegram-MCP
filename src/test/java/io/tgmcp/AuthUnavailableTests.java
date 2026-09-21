package io.tgmcp;

import io.tgmcp.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * There is no password fallback any more: on a deployment that has never configured "Sign in
 * with Telegram" ({@code tgmcp.telegram-login.bot-token}/{@code bot-username}), signing in and
 * registering must render a clear "not available" state instead of a broken or misleading form.
 * A separate {@link TestPropertySource} context from every other test, which enables the
 * feature by default (see {@code application-test.yml}).
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "tgmcp.telegram-login.bot-token=",
        "tgmcp.telegram-login.bot-username="
})
class AuthUnavailableTests extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void loginShowsUnavailableInsteadOfABrokenForm() throws Exception {
        this.mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Sign-in is not available")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("name=\"identifier\""))));
    }

    @Test
    void postingLoginWhileDisabledNeverSendsACode() throws Exception {
        this.mockMvc.perform(post("/login").with(csrf()).param("identifier", "nobody@example.invalid"))
                .andExpect(status().is3xxRedirection());
        assertThat(this.telegramBotMessenger.sent()).isEmpty();
    }

    @Test
    void registerShowsUnavailableInsteadOfABrokenForm() throws Exception {
        this.mockMvc.perform(get("/register"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Registration is not available")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("name=\"workspaceName\""))));
    }

    @Test
    void postingRegisterWhileDisabledCreatesNoAccount() throws Exception {
        this.mockMvc.perform(post("/register").with(csrf())
                        .param("workspaceName", "Should Not Exist")
                        .param("email", "should-not-exist@example.invalid"))
                .andExpect(status().is3xxRedirection());

        Long accounts = this.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM app_users WHERE lower(email) = 'should-not-exist@example.invalid'",
                Long.class);
        assertThat(accounts).isZero();
    }
}
