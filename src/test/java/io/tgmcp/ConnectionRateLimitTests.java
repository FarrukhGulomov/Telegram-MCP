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
 * native TDLib work, such as a full chat discovery pass) previously shared no budget distinct
 * from ordinary authenticated page views. A tiny per-test budget, set via {@link
 * TestPropertySource} rather than the shared test profile's generous default, makes the limit
 * reachable without looping hundreds of times. Signing in is now the same act as connecting
 * ({@code PreAuthLoginController}), so the remaining per-user-budgeted action on the (now
 * read-only) Connect page is {@code /connect/discover}.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "tgmcp.abuse-controls.connect-actions-per-minute-per-user=2")
class ConnectionRateLimitTests extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void blocksOnboardingActionsOnceThePerUserBudgetIsSpent() throws Exception {
        Fixtures.Team team = this.fixtures.team("sec10-connect");
        MockHttpSession session = signInViaPhoneNumber(this.mockMvc, team.user().phoneNumber());

        this.mockMvc.perform(post("/connect/discover").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("message"));
        this.mockMvc.perform(post("/connect/discover").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("message"));

        // Third call within the same minute: budget of 2 is spent.
        this.mockMvc.perform(post("/connect/discover").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.notNullValue()));
    }

    @Test
    void aDifferentUserHasItsOwnIndependentBudget() throws Exception {
        Fixtures.Team teamA = this.fixtures.team("sec10-connect-a");
        Fixtures.Team teamB = this.fixtures.team("sec10-connect-b");
        MockHttpSession sessionA = signInViaPhoneNumber(this.mockMvc, teamA.user().phoneNumber());
        MockHttpSession sessionB = signInViaPhoneNumber(this.mockMvc, teamB.user().phoneNumber());

        // Spend A's budget entirely.
        this.mockMvc.perform(post("/connect/discover").session(sessionA).with(csrf()));
        this.mockMvc.perform(post("/connect/discover").session(sessionA).with(csrf()));

        // B is untouched.
        this.mockMvc.perform(post("/connect/discover").session(sessionB).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("message"));
    }
}
