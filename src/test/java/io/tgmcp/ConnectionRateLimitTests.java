package io.tgmcp;

import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

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

    @Test
    void blocksOnboardingActionsOnceThePerUserBudgetIsSpent() throws Exception {
        Fixtures.Team team = this.fixtures.signInReadyTeam("sec10-connect", "sec10@connect.invalid");
        MockHttpSession session = signInViaTelegramCode(this.mockMvc, "sec10@connect.invalid",
                team.user().telegramUserId());

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
        Fixtures.Team teamA = this.fixtures.signInReadyTeam("sec10-connect-a", "sec10a@connect.invalid");
        Fixtures.Team teamB = this.fixtures.signInReadyTeam("sec10-connect-b", "sec10b@connect.invalid");
        MockHttpSession sessionA = signInViaTelegramCode(this.mockMvc, "sec10a@connect.invalid",
                teamA.user().telegramUserId());
        MockHttpSession sessionB = signInViaTelegramCode(this.mockMvc, "sec10b@connect.invalid",
                teamB.user().telegramUserId());

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
