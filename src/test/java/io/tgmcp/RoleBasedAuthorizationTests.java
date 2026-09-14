package io.tgmcp;

import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @Autowired
    private PasswordEncoder passwordEncoder;

    @LocalServerPort
    private int port;

    private MockHttpSession signIn(String email, String password) throws Exception {
        MvcResult signedIn = this.mockMvc.perform(post("/login").with(csrf())
                        .param("username", email).param("password", password))
                .andReturn();
        return (MockHttpSession) signedIn.getRequest().getSession(false);
    }

    @Test
    void aMemberGets403OnAManagementPost() throws Exception {
        String password = "member-pw";
        Fixtures.Team team = this.fixtures.signInReadyTeam("f17-member", "member@f17.invalid",
                this.passwordEncoder.encode(password), "MEMBER");
        MockHttpSession session = signIn("member@f17.invalid", password);
        assertThat(session).as("MEMBER must still be able to sign in").isNotNull();

        this.mockMvc.perform(post("/connect/start").session(session).with(csrf())
                        .param("method", "QR_CODE"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aMemberCanStillViewPages() throws Exception {
        String password = "member-pw-2";
        this.fixtures.signInReadyTeam("f17-member-view", "member2@f17.invalid",
                this.passwordEncoder.encode(password), "MEMBER");
        MockHttpSession session = signIn("member2@f17.invalid", password);

        this.mockMvc.perform(get("/").session(session))
                .andExpect(status().isOk());
        this.mockMvc.perform(get("/connect").session(session))
                .andExpect(status().isOk());
    }

    @Test
    void anOwnerCanStillPerformManagementActions() throws Exception {
        String password = "owner-pw";
        this.fixtures.signInReadyTeam("f17-owner", "owner@f17.invalid",
                this.passwordEncoder.encode(password), "OWNER");
        MockHttpSession session = signIn("owner@f17.invalid", password);

        this.mockMvc.perform(post("/connect/start").session(session).with(csrf())
                        .param("method", "QR_CODE"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void anAdminCanStillPerformManagementActions() throws Exception {
        String password = "admin-pw";
        this.fixtures.signInReadyTeam("f17-admin", "admin@f17.invalid",
                this.passwordEncoder.encode(password), "ADMIN");
        MockHttpSession session = signIn("admin@f17.invalid", password);

        this.mockMvc.perform(post("/connect/start").session(session).with(csrf())
                        .param("method", "QR_CODE"))
                .andExpect(status().is3xxRedirection());
    }
}
