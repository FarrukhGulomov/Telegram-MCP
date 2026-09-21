package io.tgmcp;

import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security-audit finding F17: {@code AppUser} carries an OWNER/ADMIN/MEMBER role, and {@link
 * io.tgmcp.security.DatabaseUserDetailsService} already turns it into a granted {@code
 * ROLE_*} authority -- but the management UI's security chain only ever required {@code
 * authenticated()}, so any signed-in user of a tenant, regardless of role, could pause,
 * revoke, delete data for, or reconfigure any chat, or drive its Telegram connection.
 */
@AutoConfigureMockMvc
class RoleBasedAuthorizationTests extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @LocalServerPort
    private int port;

    @Test
    void aMemberGets403OnAManagementPost() throws Exception {
        Fixtures.Team team = this.fixtures.signInReadyTeam("f17-member", "member@f17.invalid", "MEMBER");
        MockHttpSession session = signInViaTelegramCode(this.mockMvc, "member@f17.invalid",
                team.user().telegramUserId());
        assertThat(session).as("MEMBER must still be able to sign in").isNotNull();

        this.mockMvc.perform(post("/connect/start").session(session).with(csrf())
                        .param("method", "QR_CODE"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aMemberCanStillViewPages() throws Exception {
        Fixtures.Team team = this.fixtures.signInReadyTeam("f17-member-view", "member2@f17.invalid", "MEMBER");
        MockHttpSession session = signInViaTelegramCode(this.mockMvc, "member2@f17.invalid",
                team.user().telegramUserId());

        this.mockMvc.perform(get("/").session(session))
                .andExpect(status().isOk());
        this.mockMvc.perform(get("/connect").session(session))
                .andExpect(status().isOk());
    }

    @Test
    void anOwnerCanStillPerformManagementActions() throws Exception {
        Fixtures.Team team = this.fixtures.signInReadyTeam("f17-owner", "owner@f17.invalid", "OWNER");
        MockHttpSession session = signInViaTelegramCode(this.mockMvc, "owner@f17.invalid",
                team.user().telegramUserId());

        this.mockMvc.perform(post("/connect/start").session(session).with(csrf())
                        .param("method", "QR_CODE"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void anAdminCanStillPerformManagementActions() throws Exception {
        Fixtures.Team team = this.fixtures.signInReadyTeam("f17-admin", "admin@f17.invalid", "ADMIN");
        MockHttpSession session = signInViaTelegramCode(this.mockMvc, "admin@f17.invalid",
                team.user().telegramUserId());

        this.mockMvc.perform(post("/connect/start").session(session).with(csrf())
                        .param("method", "QR_CODE"))
                .andExpect(status().is3xxRedirection());
    }

    /**
     * Security-audit finding SEC-09: WebSecurityConfig's {@code hasAnyRole("OWNER", "ADMIN")}
     * check (F17) is only ever evaluated against the authorities baked into the session's
     * {@code Authentication} at sign-in time. Demoting this user afterwards leaves the
     * session itself still satisfying that check -- only {@code CurrentActor.requireAdmin()},
     * re-reading the role fresh from the database on every request, can catch it.
     */
    @Test
    void aDemotedOwnersExistingSessionCanNoLongerPerformManagementActions() throws Exception {
        Fixtures.Team team = this.fixtures.signInReadyTeam("sec09-demoted", "demoted@sec09.invalid", "OWNER");
        MockHttpSession session = signInViaTelegramCode(this.mockMvc, "demoted@sec09.invalid",
                team.user().telegramUserId());

        this.mockMvc.perform(post("/connect/start").session(session).with(csrf())
                        .param("method", "QR_CODE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/connect"));

        this.jdbcTemplate.update("UPDATE app_users SET role = 'MEMBER' WHERE id = ?", team.user().id());

        // The session's own stale ROLE_OWNER authority still passes Spring Security's own
        // check, so only the fresh application-level check in requireAdmin() can reject this.
        this.mockMvc.perform(post("/connect/start").session(session).with(csrf())
                        .param("method", "QR_CODE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    /**
     * Security-audit finding SEC-09: disabling a user must stop their already-signed-in
     * session immediately, not merely block their next sign-in attempt.
     */
    @Test
    void aDisabledUsersExistingSessionCanNoLongerDoAnything() throws Exception {
        Fixtures.Team team = this.fixtures.signInReadyTeam("sec09-disabled", "disabled@sec09.invalid", "OWNER");
        MockHttpSession session = signInViaTelegramCode(this.mockMvc, "disabled@sec09.invalid",
                team.user().telegramUserId());

        this.mockMvc.perform(get("/connect").session(session)).andExpect(status().isOk());

        this.jdbcTemplate.update("UPDATE app_users SET enabled = false WHERE id = ?", team.user().id());

        // A disabled account's existing session must stop working immediately.
        this.mockMvc.perform(get("/connect").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }
}
