package io.tgmcp;

import java.time.Instant;
import java.util.UUID;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Self-service account security: changing a password, listing and revoking connected MCP
 * (OAuth) clients, and locking an account out after repeated bad passwords -- the three gaps
 * a security review of the account-takeover path found (a leaked password previously had no
 * self-service recovery, no visibility into what it had been used to authorize, and no
 * per-account brute-force slowdown beyond the shared per-IP budget).
 *
 * <p>{@code accountLockThreshold} is lowered to 3 so the lockout test is reachable without
 * looping hundreds of times; the other tests here never submit a bad password, so this is safe
 * to share.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "tgmcp.abuse-controls.account-lock-threshold=3")
class AccountSecurityTests extends AbstractIntegrationTest {

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
    void changingToAWrongCurrentPasswordIsRejected() throws Exception {
        String password = "account-pw-1";
        this.fixtures.signInReadyTeam("acct-a", "acct-a@example.invalid", this.passwordEncoder.encode(password));
        MockHttpSession session = signIn("acct-a@example.invalid", password);

        this.mockMvc.perform(post("/account/password").session(session).with(csrf())
                        .param("currentPassword", "not-the-real-password")
                        .param("newPassword", "brand-new-password")
                        .param("newPasswordConfirm", "brand-new-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.notNullValue()));

        // The old password must still work.
        assertThat(signIn("acct-a@example.invalid", password)).isNotNull();
    }

    @Test
    void mismatchedNewPasswordsAreRejected() throws Exception {
        String password = "account-pw-2";
        this.fixtures.signInReadyTeam("acct-b", "acct-b@example.invalid", this.passwordEncoder.encode(password));
        MockHttpSession session = signIn("acct-b@example.invalid", password);

        this.mockMvc.perform(post("/account/password").session(session).with(csrf())
                        .param("currentPassword", password)
                        .param("newPassword", "brand-new-password")
                        .param("newPasswordConfirm", "does-not-match"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.notNullValue()));
    }

    @Test
    void aCorrectCurrentPasswordActuallyChangesIt() throws Exception {
        String oldPassword = "account-pw-3";
        String newPassword = "brand-new-password-3";
        this.fixtures.signInReadyTeam("acct-c", "acct-c@example.invalid", this.passwordEncoder.encode(oldPassword));
        MockHttpSession session = signIn("acct-c@example.invalid", oldPassword);

        this.mockMvc.perform(post("/account/password").session(session).with(csrf())
                        .param("currentPassword", oldPassword)
                        .param("newPassword", newPassword)
                        .param("newPasswordConfirm", newPassword))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("message"));

        assertThat(signIn("acct-c@example.invalid", newPassword))
                .as("the new password must now sign the account in")
                .isNotNull();
    }

    @Test
    void connectedAppsListsAndRevokesAnAuthorization() throws Exception {
        String password = "account-pw-4";
        Fixtures.Team team = this.fixtures.signInReadyTeam("acct-d", "acct-d@example.invalid",
                this.passwordEncoder.encode(password));
        MockHttpSession session = signIn("acct-d@example.invalid", password);

        String clientRowId = UUID.randomUUID().toString();
        this.jdbcTemplate.update("""
                        INSERT INTO oauth2_registered_client
                            (id, client_id, client_name, client_authentication_methods,
                             authorization_grant_types, scopes, client_settings, token_settings)
                        VALUES (?, ?, 'Claude', 'none', 'authorization_code', 'mcp:read', '{}', '{}')
                        """,
                clientRowId, "claude-test-client");
        this.jdbcTemplate.update("""
                        INSERT INTO oauth2_authorization
                            (id, registered_client_id, principal_name, authorization_grant_type,
                             authorized_scopes, access_token_value, access_token_issued_at)
                        VALUES (?, ?, ?, 'authorization_code', 'mcp:read', 'token-value', ?)
                        """,
                UUID.randomUUID().toString(), clientRowId, team.user().email(),
                java.sql.Timestamp.from(Instant.now()));
        this.jdbcTemplate.update("""
                        INSERT INTO oauth2_authorization_consent (registered_client_id, principal_name, authorities)
                        VALUES (?, ?, 'SCOPE_mcp:read')
                        """,
                clientRowId, team.user().email());

        this.mockMvc.perform(get("/account").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Claude")));

        this.mockMvc.perform(post("/account/connections/revoke").session(session).with(csrf())
                        .param("clientId", clientRowId))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("message"));

        Integer remainingAuthorizations = this.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM oauth2_authorization WHERE registered_client_id = ? AND principal_name = ?",
                Integer.class, clientRowId, team.user().email());
        Integer remainingConsents = this.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM oauth2_authorization_consent WHERE registered_client_id = ? "
                        + "AND principal_name = ?",
                Integer.class, clientRowId, team.user().email());
        assertThat(remainingAuthorizations).isZero();
        assertThat(remainingConsents)
                .as("the consent decision must be removed too, or the client could silently "
                        + "re-obtain a code with no consent screen shown again")
                .isZero();
    }

    @Test
    void repeatedWrongPasswordsLockTheAccountEvenAgainstTheCorrectOne() throws Exception {
        String password = "account-pw-5";
        this.fixtures.signInReadyTeam("acct-e", "acct-e@example.invalid", this.passwordEncoder.encode(password));

        for (int i = 0; i < 3; i++) {
            this.mockMvc.perform(post("/login").with(csrf())
                            .param("username", "acct-e@example.invalid").param("password", "wrong-password-" + i))
                    .andExpect(redirectedUrl("/login?error"));
        }

        this.mockMvc.perform(post("/login").with(csrf())
                        .param("username", "acct-e@example.invalid").param("password", password))
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void aDifferentAccountIsNotAffectedByAnothersLockout() throws Exception {
        String passwordA = "account-pw-6a";
        String passwordB = "account-pw-6b";
        this.fixtures.signInReadyTeam("acct-f", "acct-f@example.invalid", this.passwordEncoder.encode(passwordA));
        this.fixtures.signInReadyTeam("acct-g", "acct-g@example.invalid", this.passwordEncoder.encode(passwordB));

        for (int i = 0; i < 3; i++) {
            this.mockMvc.perform(post("/login").with(csrf())
                    .param("username", "acct-f@example.invalid").param("password", "wrong-password-" + i));
        }

        this.mockMvc.perform(post("/login").with(csrf())
                        .param("username", "acct-g@example.invalid").param("password", passwordB))
                .andExpect(redirectedUrl("/"));
    }
}
