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
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The user-requested alternative to opening each discovered chat one at a time: check the ones
 * you want on the dashboard list and press one shared button, whatever state each one is
 * currently in. {@link io.tgmcp.ui.DashboardController#bulkPrepare} (re)proposes with defaults
 * only for a chat that was never touched or that was fully REVOKED (its settings are gone);
 * anything else already has a working purpose/providers/date range, so it is activated directly
 * without resetting that. {@link io.tgmcp.ui.DashboardController#bulkPause} is the same
 * checkbox-driven idea for turning several ACTIVE chats off at once. Per this deployment's
 * explicit operator decision (see {@link io.tgmcp.consent.ConsentService#coverageFor}), no
 * separate per-participant consent step exists to skip any more.
 */
@AutoConfigureMockMvc
class DashboardBulkPrepareTests extends AbstractIntegrationTest {

    private static final String EMAIL = "owner@demo.invalid";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChatRepository chats;

    @Autowired
    private ChatActivationRepository activations;

    @Autowired
    private TelegramConnectionRepository connections;

    private MockHttpSession signInAndConnect(Fixtures.Team team) throws Exception {
        MockHttpSession session = signInViaPhoneNumber(this.mockMvc, team.user().phoneNumber());
        this.mockMvc.perform(post("/connect/discover").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());
        return session;
    }

    @Test
    void activatesEveryCheckedChatWithDefaultsAndLeavesUncheckedOnesAlone() throws Exception {
        Fixtures.Team team = this.fixtures.signInReadyTeam("bulk-a", EMAIL);
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
    void activatesAnAlreadySelectedChatWithoutResettingItsSettings() throws Exception {
        Fixtures.Team team = this.fixtures.signInReadyTeam("bulk-c", EMAIL);
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
        assertThat(this.activations.find(team.tenant().id(), engineering.id()).orElseThrow().state())
                .as("an AWAITING_CONSENT chat checked on the dashboard must actually turn on")
                .isEqualTo(ActivationState.ACTIVE);
    }

    /**
     * The exact complaint this bulk action exists to fix: a chat the operator had already turned
     * on and later paused (e.g. after a Telegram-side hiccup) used to require opening its own
     * page and pressing "Activate" there individually. Checking it on the dashboard and pressing
     * the same shared button must turn it back on, keeping the purpose/providers/dates it already
     * had rather than resetting them to the bulk defaults.
     */
    @Test
    void reactivatesAPausedChatWithoutResettingItsSettings() throws Exception {
        Fixtures.Team team = this.fixtures.signInReadyTeam("bulk-e", EMAIL);
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
        this.mockMvc.perform(post("/chats/" + engineering.id() + "/activate").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());
        this.mockMvc.perform(post("/chats/" + engineering.id() + "/pause").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(this.activations.find(team.tenant().id(), engineering.id()).orElseThrow().state())
                .isEqualTo(ActivationState.PAUSED);

        this.mockMvc.perform(post("/chats/bulk-prepare").session(session).with(csrf())
                        .param("chatId", engineering.id().toString()))
                .andExpect(status().is3xxRedirection());

        var reactivated = this.activations.find(team.tenant().id(), engineering.id()).orElseThrow();
        assertThat(reactivated.state()).isEqualTo(ActivationState.ACTIVE);
        assertThat(reactivated.purpose()).isEqualTo("A specific, already-chosen purpose");
    }

    /**
     * A REVOKED chat's activation row carries no meaningful settings any more (revoke() also
     * purges the stored messages), so there is nothing to preserve -- reactivating it through the
     * bulk button falls back to the same defaults a never-touched chat gets.
     */
    @Test
    void reactivatesARevokedChatUsingDefaultsSinceItsSettingsWerePurged() throws Exception {
        Fixtures.Team team = this.fixtures.signInReadyTeam("bulk-f", EMAIL);
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
        this.mockMvc.perform(post("/chats/" + engineering.id() + "/activate").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());
        this.mockMvc.perform(post("/chats/" + engineering.id() + "/revoke").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(this.activations.find(team.tenant().id(), engineering.id()).orElseThrow().state())
                .isEqualTo(ActivationState.REVOKED);

        this.mockMvc.perform(post("/chats/bulk-prepare").session(session).with(csrf())
                        .param("chatId", engineering.id().toString()))
                .andExpect(status().is3xxRedirection());

        assertThat(this.activations.find(team.tenant().id(), engineering.id()).orElseThrow().state())
                .isEqualTo(ActivationState.ACTIVE);
    }

    @Test
    void doesNothingWhenNoChatIsChecked() throws Exception {
        Fixtures.Team team = this.fixtures.signInReadyTeam("bulk-d", EMAIL);
        MockHttpSession session = signInAndConnect(team);

        this.mockMvc.perform(post("/chats/bulk-prepare").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void bulkPausePausesEveryCheckedActiveChatAndLeavesUncheckedOnesAlone() throws Exception {
        Fixtures.Team team = this.fixtures.signInReadyTeam("bulk-g", EMAIL);
        MockHttpSession session = signInAndConnect(team);
        TelegramConnection connection = this.connections.findByOwner(team.tenant().id(), team.user().id())
                .orElseThrow();
        List<Chat> discovered = this.chats.findByConnection(team.tenant().id(), connection.id());
        Chat engineering = discovered.stream()
                .filter(c -> c.tgChatId() == SyntheticDataset.ENGINEERING_CHAT).findFirst().orElseThrow();
        Chat launchOps = discovered.stream()
                .filter(c -> c.tgChatId() == SyntheticDataset.LAUNCH_OPS_CHAT).findFirst().orElseThrow();
        this.mockMvc.perform(post("/chats/bulk-prepare").session(session).with(csrf())
                        .param("chatId", engineering.id().toString(), launchOps.id().toString()))
                .andExpect(status().is3xxRedirection());

        this.mockMvc.perform(post("/chats/bulk-pause").session(session).with(csrf())
                        .param("chatId", engineering.id().toString()))
                .andExpect(status().is3xxRedirection());

        assertThat(this.activations.find(team.tenant().id(), engineering.id()).orElseThrow().state())
                .isEqualTo(ActivationState.PAUSED);
        assertThat(this.activations.find(team.tenant().id(), launchOps.id()).orElseThrow().state())
                .as("a chat left unchecked on the pause form must stay ACTIVE")
                .isEqualTo(ActivationState.ACTIVE);
    }

    @Test
    void bulkPauseLeavesANeverActivatedChatAlone() throws Exception {
        Fixtures.Team team = this.fixtures.signInReadyTeam("bulk-h", EMAIL);
        MockHttpSession session = signInAndConnect(team);
        TelegramConnection connection = this.connections.findByOwner(team.tenant().id(), team.user().id())
                .orElseThrow();
        Chat engineering = this.chats.findByConnection(team.tenant().id(), connection.id()).stream()
                .filter(c -> c.tgChatId() == SyntheticDataset.ENGINEERING_CHAT).findFirst().orElseThrow();

        this.mockMvc.perform(post("/chats/bulk-pause").session(session).with(csrf())
                        .param("chatId", engineering.id().toString()))
                .andExpect(status().is3xxRedirection());

        assertThat(this.activations.find(team.tenant().id(), engineering.id())).isEmpty();
    }
}
