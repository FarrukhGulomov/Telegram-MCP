package io.tgmcp;

import java.util.UUID;

import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The cross-tenant {@code /platform-admin} overview must be reachable only by the phone
 * numbers configured under {@code tgmcp.platform-admin.phone-numbers} (see {@code
 * CurrentActor#requirePlatformAdmin}), and once reached it must genuinely show data from every
 * tenant, not just the caller's own -- that cross-tenant reach is the entire point of this page,
 * unlike the tenant-scoped {@code /audit} page every signed-in user already has.
 */
@AutoConfigureMockMvc
class PlatformAdminAccessTests extends AbstractIntegrationTest {

    private static final String ADMIN_SEED = "platform-admin-owner-seed";

    @Autowired
    private MockMvc mockMvc;

    /**
     * Runs before the Spring context exists, so it cannot use the {@link Fixtures} bean -- it
     * only needs {@link Fixtures#phoneNumberFor}, a pure function of the seed string, to
     * pre-register the exact phone number a later {@code signInReadyTeam(slug, ADMIN_SEED)}
     * will derive.
     */
    @DynamicPropertySource
    static void platformAdminAllowlist(DynamicPropertyRegistry registry) {
        registry.add("tgmcp.platform-admin.phone-numbers", () -> Fixtures.phoneNumberFor(ADMIN_SEED));
    }

    @Test
    void aNonAllowlistedSignedInUserCannotReachThePage() throws Exception {
        Fixtures.Team team = this.fixtures.signInReadyTeam("padmin-outsider", "outsider@padmin.invalid");
        MockHttpSession session = signInViaPhoneNumber(this.mockMvc, team.user().phoneNumber());

        this.mockMvc.perform(get("/platform-admin").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void anAllowlistedOperatorSeesEveryTenantNotJustTheirOwn() throws Exception {
        Fixtures.Team adminTeam = this.fixtures.signInReadyTeam("padmin-operator", ADMIN_SEED);
        Fixtures.Team otherTeam = this.fixtures.signInReadyTeam("padmin-other-tenant", "someone-elses-seed");

        // A denied access attempt recorded against the OTHER tenant, never the admin's own --
        // proves the merged feed genuinely spans tenants rather than only aggregating counts.
        this.jdbcTemplate.update("""
                        INSERT INTO audit_events (id, tenant_id, actor_type, actor_id, action, outcome, detail)
                        VALUES (?, ?, 'MCP_CLIENT', 'suspicious-test-actor', 'MCP_ACCESS_DENIED', 'DENIED',
                                '{}'::jsonb)
                        """,
                UUID.randomUUID(), otherTeam.tenant().id());

        MockHttpSession session = signInViaPhoneNumber(this.mockMvc, adminTeam.user().phoneNumber());

        this.mockMvc.perform(get("/platform-admin").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("padmin-operator")))
                .andExpect(content().string(Matchers.containsString("padmin-other-tenant")))
                .andExpect(content().string(Matchers.containsString("suspicious-test-actor")));
    }

    @Test
    void theNavLinkIsHiddenForNonAdminsAndShownForTheAllowlistedOperator() throws Exception {
        Fixtures.Team outsider = this.fixtures.signInReadyTeam("padmin-nav-outsider", "nav-outsider-seed");
        MockHttpSession outsiderSession = signInViaPhoneNumber(this.mockMvc, outsider.user().phoneNumber());
        this.mockMvc.perform(get("/").session(outsiderSession))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString("/platform-admin"))));

        Fixtures.Team adminTeam = this.fixtures.signInReadyTeam("padmin-nav-operator", ADMIN_SEED);
        MockHttpSession adminSession = signInViaPhoneNumber(this.mockMvc, adminTeam.user().phoneNumber());
        this.mockMvc.perform(get("/").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("/platform-admin")));
    }
}
