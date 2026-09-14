package io.tgmcp;

import io.tgmcp.chat.Chat;
import io.tgmcp.chat.ChatActivationRepository;
import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import io.tgmcp.telegram.mock.SyntheticDataset;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression coverage for a real bug found in a Railway test deployment: submitting the
 * "select chat" form with every provider checkbox left unchecked returned a raw, unstyled 400
 * (browsers omit an unchecked checkbox's field entirely, so Spring saw a missing required
 * {@code List<String> providers} parameter and rejected the request before the controller body
 * — and its existing "select at least one provider" validation — ever ran).
 *
 * <p>{@link io.tgmcp.ui.ChatController} now accepts a possibly-absent {@code providers} list and
 * lets the normal validation produce a friendly, in-page error instead.
 */
@AutoConfigureMockMvc
class ChatFormValidationTests extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChatActivationRepository activations;

    private MockHttpSession signIn(Fixtures.Team team) throws Exception {
        MvcResult signedIn = this.mockMvc.perform(post("/login").with(csrf())
                        .param("username", team.user().email())
                        .param("password", "pw"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) signedIn.getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
    }

    @Test
    void selectingAChatWithNoProviderCheckedRedirectsBackWithAFriendlyErrorInsteadOf400() throws Exception {
        Fixtures.Team team = this.fixtures.team("select-form-validation");
        Chat chat = this.fixtures.chat(team, SyntheticDataset.ENGINEERING_CHAT, "No providers chat", "no_providers_chat");
        MockHttpSession session = signIn(team);

        this.mockMvc.perform(post("/chats/" + chat.id() + "/select").session(session).with(csrf())
                        .param("purpose", "Answer questions about team decisions")
                        // No "providers" param at all: this is what an unchecked checkbox group submits.
                        .param("approvedFrom", "2026-01-01")
                        .param("approvedTo", "2026-12-31")
                        .param("retentionDays", "365"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/chats/" + chat.id()))
                .andExpect(flash().attribute("error", "Select at least one provider"));
    }

    @Test
    void savingSettingsWithNoProviderCheckedRedirectsBackWithAFriendlyErrorInsteadOf400() throws Exception {
        Fixtures.Team team = this.fixtures.team("settings-form-validation");
        Chat chat = this.fixtures.chat(team, SyntheticDataset.LAUNCH_OPS_CHAT, "No providers chat", "no_providers_chat2");
        MockHttpSession session = signIn(team);

        // Select it first so the /settings endpoint (which requires an existing activation) applies.
        this.mockMvc.perform(post("/chats/" + chat.id() + "/select").session(session).with(csrf())
                        .param("purpose", "Answer questions about team decisions")
                        .param("providers", io.tgmcp.security.McpScopes.ANTHROPIC_CLAUDE)
                        .param("approvedFrom", "2026-01-01")
                        .param("approvedTo", "2026-12-31")
                        .param("retentionDays", "365"))
                .andExpect(status().is3xxRedirection());

        this.mockMvc.perform(post("/chats/" + chat.id() + "/settings").session(session).with(csrf())
                        .param("purpose", "Answer questions about team decisions")
                        .param("approvedFrom", "2026-01-01")
                        .param("approvedTo", "2026-12-31")
                        .param("retentionDays", "365"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/chats/" + chat.id()))
                .andExpect(flash().attribute("error", "Select at least one provider"));
    }

    /**
     * Security-review finding: {@code providers} is only ever offered as fixed checkboxes in
     * chat.html, but a direct POST (bypassing the browser UI) can name anything. Before this
     * whitelist check, an unrecognized provider would be persisted as the chat's providerScope
     * and later crash the chat/audit page's {@code #{'provider.' + value}} message lookup with
     * a 500 -- not an access-control bypass (no real MCP token could ever match a made-up
     * provider), but a self-inflicted denial of service on that chat's page.
     */
    @Test
    void selectingAChatWithAnUnrecognizedProviderIsRejectedInsteadOfStoredAndCrashingLater() throws Exception {
        Fixtures.Team team = this.fixtures.team("unknown-provider-validation");
        Chat chat = this.fixtures.chat(team, SyntheticDataset.ENGINEERING_CHAT, "Unknown provider chat", "unknown_provider_chat");
        MockHttpSession session = signIn(team);

        this.mockMvc.perform(post("/chats/" + chat.id() + "/select").session(session).with(csrf())
                        .param("purpose", "Answer questions about team decisions")
                        .param("providers", "NOT_A_REAL_PROVIDER")
                        .param("approvedFrom", "2026-01-01")
                        .param("approvedTo", "2026-12-31")
                        .param("retentionDays", "365"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/chats/" + chat.id()))
                .andExpect(flash().attribute("error",
                        "One of the selected providers is not recognized. Please use only the checkboxes provided."));

        assertThat(this.activations.find(team.tenant().id(), chat.id())).isEmpty();
    }
}
