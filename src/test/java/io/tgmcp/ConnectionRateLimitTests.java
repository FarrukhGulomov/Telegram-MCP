package io.tgmcp;

import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security-audit finding SEC-10: Telegram onboarding actions (which can trigger real, costly
 * native TDLib work) previously shared no budget distinct from ordinary authenticated page
 * views. A tiny per-test budget, set via {@link TestPropertySource} rather than the shared
 * test profile's generous default, makes the limit reachable without looping hundreds of
 * times.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "tgmcp.abuse-controls.connect-actions-per-minute-per-user=2")
class ConnectionRateLimitTests extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockHttpSession signIn(String email, String password) throws Exception {
        MvcResult signedIn = this.mockMvc.perform(post("/login").with(csrf())
                        .param("username", email).param("password", password))
                .andReturn();
        return (MockHttpSession) signedIn.getRequest().getSession(false);
    }

    @Test
    void blocksOnboardingActionsOnceThePerUserBudgetIsSpent() throws Exception {
        String password = "sec10-pw";
        this.fixtures.signInReadyTeam("sec10-connect", "sec10@connect.invalid",
                this.passwordEncoder.encode(password));
        MockHttpSession session = signIn("sec10@connect.invalid", password);

        this.mockMvc.perform(post("/connect/start").session(session).with(csrf())
                        .param("method", "QR_CODE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("message"));
        this.mockMvc.perform(post("/connect/start").session(session).with(csrf())
                        .param("method", "QR_CODE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("message"));

        // Third call within the same minute: budget of 2 is spent.
        this.mockMvc.perform(post("/connect/start").session(session).with(csrf())
                        .param("method", "QR_CODE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.notNullValue()));
    }

    @Test
    void aDifferentUserHasItsOwnIndependentBudget() throws Exception {
        String passwordA = "sec10-pw-a";
        String passwordB = "sec10-pw-b";
        this.fixtures.signInReadyTeam("sec10-connect-a", "sec10a@connect.invalid",
                this.passwordEncoder.encode(passwordA));
        this.fixtures.signInReadyTeam("sec10-connect-b", "sec10b@connect.invalid",
                this.passwordEncoder.encode(passwordB));
        MockHttpSession sessionA = signIn("sec10a@connect.invalid", passwordA);
        MockHttpSession sessionB = signIn("sec10b@connect.invalid", passwordB);

        // Spend A's budget entirely.
        this.mockMvc.perform(post("/connect/start").session(sessionA).with(csrf())
                .param("method", "QR_CODE"));
        this.mockMvc.perform(post("/connect/start").session(sessionA).with(csrf())
                .param("method", "QR_CODE"));

        // B is untouched.
        this.mockMvc.perform(post("/connect/start").session(sessionB).with(csrf())
                        .param("method", "QR_CODE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("message"));
    }
}
