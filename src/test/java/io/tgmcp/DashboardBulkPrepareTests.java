package io.tgmcp;

import java.util.List;

import io.tgmcp.chat.ActivationState;
import io.tgmcp.chat.Chat;
import io.tgmcp.chat.ChatActivationRepository;
import io.tgmcp.chat.ChatRepository;
import io.tgmcp.security.McpScopes;
import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import io.tgmcp.telegram.TelegramConnection;
import io.tgmcp.telegram.TelegramConnectionRepository;
import io.tgmcp.telegram.mock.SyntheticDataset;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The user-requested alternative to selecting each discovered chat one at a time: check the ones
 * you want, press one shared button, and they turn on. {@link
 * io.tgmcp.ui.DashboardController#bulkPrepare} proposes (with defaults, importing participants)
 * and immediately activates every checked, not-yet-selected chat -- per this deployment's
 * explicit operator decision (see {@link io.tgmcp.consent.ConsentService#coverageFor}), no
 * separate per-participant consent step exists to skip any more.
 */
@AutoConfigureMockMvc
class DashboardBulkPrepareTests extends AbstractIntegrationTest {

    private static final String EMAIL = "owner@demo.invalid";
    private static final String PASSWORD = "demo-password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ChatRepository chats;

    @Autowired
    private ChatActivationRepository activations;

    @Autowired
    private TelegramConnectionRepository connections;

    private MockHttpSession signInAndConnect(Fixtures.Team team) throws Exception {
        MvcResult signedIn = this.mockMvc.perform(post("/login").with(csrf())
                        .param("username", team.user().email()).param("password", PASSWORD))
                .andReturn();
        MockHttpSession session = (MockHttpSession) signedIn.getRequest().getSession(false);
        this.mockMvc.perform(post("/connect/start").session(session).with(csrf())
                .param("method", "QR_CODE")).andExpect(status().is3xxRedirection());
        this.mockMvc.perform(post("/connect/code").session(session).with(csrf())
                .param("code", "12345")).andExpect(status().is3xxRedirection());
        this.mockMvc.perform(post("/connect/password").session(session).with(csrf())
                .param("password", "any")).andExpect(status().is3xxRedirection());
        this.mockMvc.perform(post("/connect/discover").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());
        return session;
    }

    @Test
    void activatesEveryCheckedChatWithDefaultsAndLeavesUncheckedOnesAlone() throws Exception {
        Fixtures.Team team = this.fixtures.signInReadyTeam("bulk-a", EMAIL,
                this.passwordEncoder.encode(PASSWORD));
        MockHttpSession session = signInAndConnect(team);
        TelegramConnection connection = this.connections.findByOwner(team.tenant().id(), team.user().id())
                .orElseThrow();
        List<Chat> discovered = this.chats.findByConnection(team.tenant().id(), connection.id());
        Chat engineering = discovered.stream()
                .filter(c -> c.tgChatId() == SyntheticDataset.ENGINEERING_CHAT).findFirst().orElseThrow();
        Chat launchOps = discovered.stream()
                .filter(c -> c.tgChatId() == SyntheticDataset.LAUNCH_OPS_CHAT).findFirst().orElseThrow();
        Chat leadership = discovered.stream()
                .filter(c -> c.tgChatId() == SyntheticDataset.LEADERSHIP_CHAT).findFirst().orElseThrow();

        this.mockMvc.perform(post("/chats/bulk-prepare").session(session).with(csrf())
                        .param("chatId", engineering.id().toString(), launchOps.id().toString()))
                .andExpect(status().is3xxRedirection());

        assertThat(this.activations.find(team.tenant().id(), engineering.id()).orElseThrow().state())
                .isEqualTo(ActivationState.ACTIVE);
        assertThat(this.activations.find(team.tenant().id(), launchOps.id()).orElseThrow().state())
                .isEqualTo(ActivationState.ACTIVE);
        assertThat(this.activations.find(team.tenant().id(), leadership.id()))
                .as("a chat left unchecked must not be touched")
                .isEmpty();
    }

    @Test
    void skipsAChatThatIsAlreadySelectedRatherThanResettingItsSettings() throws Exception {
        Fixtures.Team team = this.fixtures.signInReadyTeam("bulk-c", EMAIL,
                this.passwordEncoder.encode(PASSWORD));
        MockHttpSession session = signInAndConnect(team);
        TelegramConnection connection = this.connections.findByOwner(team.tenant().id(), team.user().id())
                .orElseThrow();
        Chat engineering = this.chats.findByConnection(team.tenant().id(), connection.id()).stream()
                .filter(c -> c.tgChatId() == SyntheticDataset.ENGINEERING_CHAT).findFirst().orElseThrow();
        this.mockMvc.perform(post("/chats/" + engineering.id() + "/select").session(session).with(csrf())
                        .param("purpose", "A specific, already-chosen purpose")
                        .param("providers", McpScopes.ANTHROPIC_CLAUDE)
                        .param("approvedFrom", "2026-01-01")
                        .param("approvedTo", "2026-06-30")
                        .param("retentionDays", "30"))
                .andExpect(status().is3xxRedirection());

        this.mockMvc.perform(post("/chats/bulk-prepare").session(session).with(csrf())
                        .param("chatId", engineering.id().toString()))
                .andExpect(status().is3xxRedirection());

        assertThat(this.activations.find(team.tenant().id(), engineering.id()).orElseThrow().purpose())
                .as("bulk-prepare must not overwrite settings a real select() already recorded")
                .isEqualTo("A specific, already-chosen purpose");
    }

    @Test
    void doesNothingWhenNoChatIsChecked() throws Exception {
        Fixtures.Team team = this.fixtures.signInReadyTeam("bulk-d", EMAIL,
                this.passwordEncoder.encode(PASSWORD));
        MockHttpSession session = signInAndConnect(team);

        this.mockMvc.perform(post("/chats/bulk-prepare").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());
    }
}
