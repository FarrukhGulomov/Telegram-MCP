package io.tgmcp.security;

import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Security-audit finding SEC-13: once an operator declares {@code
 * tgmcp.forwarded-headers.trusted-hosts}, {@link TrustedForwardedHostFilter} must actually be
 * wired ahead of everything else in the real filter chain -- not just pass in isolation (see
 * {@link TrustedForwardedHostFilterTests}). Uses a separate Spring context (via {@link
 * TestPropertySource}) from every other test in this module so enabling the allowlist here
 * cannot affect any test that leaves it at its empty, no-op default.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "tgmcp.forwarded-headers.trusted-hosts=mcp.example.com")
class TrustedForwardedHostFilterIntegrationTests extends AbstractIntegrationTest {

    private static final String EMAIL = "owner@trustedhost.invalid";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsALoginAttemptWhoseForwardedHostIsNotOnTheDeclaredList() throws Exception {
        this.fixtures.signInReadyTeam("trustedhost1", EMAIL);

        this.mockMvc.perform(post("/login").with(csrf())
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "attacker-controlled.invalid")
                        .param("identifier", EMAIL))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("a forwarded host outside the declared allowlist must never reach "
                                + "the login handler, let alone build a redirect against it")
                        .isEqualTo(400));
    }

    @Test
    void allowsALoginAttemptWhoseForwardedHostIsOnTheDeclaredList() throws Exception {
        Fixtures.Team team = this.fixtures.signInReadyTeam("trustedhost2", EMAIL);

        this.mockMvc.perform(post("/login").with(csrf())
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "mcp.example.com")
                        .param("identifier", EMAIL))
                .andExpect(result -> assertThat(result.getResponse().getHeader("Location"))
                        .as("a forwarded host that matches the declared allowlist must keep "
                                + "working exactly as it did before this filter existed")
                        .startsWith("https://mcp.example.com"));
        assertThat(this.telegramBotMessenger.latestCodeFor(team.user().telegramUserId())).isNotBlank();
    }

    @Test
    void rejectsARequestWithNoForwardedHeaderAtAllWhenTheDirectHostIsNotOnTheList() throws Exception {
        // No X-Forwarded-Host means the effective host is whatever this request's own Host
        // header says (MockMvc defaults to "localhost"), which is also not on the allowlist --
        // covering the case of the application port being reachable directly, bypassing
        // whatever proxy the operator intended to be the only path in.
        this.mockMvc.perform(post("/login").with(csrf())
                        .param("identifier", EMAIL))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(400));
    }
}
