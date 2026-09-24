package io.tgmcp;

import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import io.tgmcp.telegram.gateway.LoginMethod;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security-audit finding SEC-R02: an unauthenticated {@code POST /login/start} for an existing
 * phone number starts a real Telegram login attempt against that number (a code or SMS Telegram
 * itself sends), and the pre-existing IP-keyed budget on this endpoint cannot bound how often any
 * single *number* is targeted this way, since an attacker who knows the victim's number can
 * simply spread attempts across source addresses (or wait out the per-IP window). A second,
 * phone-keyed budget (see {@code WebSecurityConfig#managementUiFilterChain}) closes that gap:
 * deliberately exercised here with no shared session or IP context between calls, since a real
 * attack would not share either. A tiny per-test budget, set via {@link TestPropertySource},
 * makes the limit reachable without looping dozens of times.
 *
 * <p>Security-audit finding H1: such an attempt no longer disrupts the number's own existing
 * Telegram connection the way it originally could ({@code PreAuthLoginService#begin} used to call
 * a {@code prepareForFreshLogin} that tore it down before any proof of ownership) -- this budget
 * remains valuable regardless, to bound how often an uninvolved number can be made to receive
 * unsolicited Telegram codes at all.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "tgmcp.abuse-controls.login-attempts-per-hour-per-phone=2")
class LoginPerPhoneRateLimitTests extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void blocksRepeatedLoginStartAttemptsForTheSamePhoneNumberRegardlessOfCaller() throws Exception {
        Fixtures.Team victim = this.fixtures.team("sec-r02-victim");
        String phoneNumber = victim.user().phoneNumber();

        this.mockMvc.perform(post("/login/start").with(csrf())
                        .param("method", LoginMethod.PHONE_NUMBER.name())
                        .param("phoneNumber", phoneNumber))
                .andExpect(status().is3xxRedirection());
        this.mockMvc.perform(post("/login/start").with(csrf())
                        .param("method", LoginMethod.PHONE_NUMBER.name())
                        .param("phoneNumber", phoneNumber))
                .andExpect(status().is3xxRedirection());

        // A third attempt against the same number, from what looks like an entirely unrelated
        // caller (no shared session, no shared IP-budget history to speak of in this test),
        // must still be rejected: the budget belongs to the phone number, not the caller.
        this.mockMvc.perform(post("/login/start").with(csrf())
                        .param("method", LoginMethod.PHONE_NUMBER.name())
                        .param("phoneNumber", phoneNumber))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void aDifferentPhoneNumberHasItsOwnIndependentBudget() throws Exception {
        Fixtures.Team victim = this.fixtures.team("sec-r02-victim-2");
        Fixtures.Team bystander = this.fixtures.team("sec-r02-bystander");

        this.mockMvc.perform(post("/login/start").with(csrf())
                .param("method", LoginMethod.PHONE_NUMBER.name())
                .param("phoneNumber", victim.user().phoneNumber()));
        this.mockMvc.perform(post("/login/start").with(csrf())
                .param("method", LoginMethod.PHONE_NUMBER.name())
                .param("phoneNumber", victim.user().phoneNumber()));

        this.mockMvc.perform(post("/login/start").with(csrf())
                        .param("method", LoginMethod.PHONE_NUMBER.name())
                        .param("phoneNumber", bystander.user().phoneNumber()))
                .andExpect(status().is3xxRedirection());
    }
}
