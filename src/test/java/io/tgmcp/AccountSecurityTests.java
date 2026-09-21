package io.tgmcp;

import java.time.Instant;
import java.util.UUID;

import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Self-service account security: listing and revoking connected MCP (OAuth) clients -- so a
 * user has visibility into, and can undo, what has been authorized against their account. There
 * is no password to change or lock out any more (see {@code LoginTwoFactorService}): every
 * sign-in already requires proving control of the linked Telegram account.
 */
@AutoConfigureMockMvc
class AccountSecurityTests extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void connectedAppsListsAndRevokesAnAuthorization() throws Exception {
        Fixtures.Team team = this.fixtures.signInReadyTeam("acct-d", "acct-d@example.invalid");
        MockHttpSession session = signInViaTelegramCode(this.mockMvc, "acct-d@example.invalid",
                team.user().telegramUserId());

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
}
