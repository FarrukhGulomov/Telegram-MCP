package io.tgmcp;

import java.util.List;

import io.tgmcp.chat.Chat;
import io.tgmcp.chat.ChatParticipantRepository;
import io.tgmcp.security.McpScopes;
import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import io.tgmcp.telegram.gateway.RemoteMembership;
import io.tgmcp.telegram.gateway.RemoteParticipant;
import io.tgmcp.telegram.gateway.TelegramAdapterKind;
import io.tgmcp.telegram.gateway.TelegramGateway;
import io.tgmcp.telegram.mock.SyntheticDataset;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real-account testing found a chat could get stuck in AWAITING_CONSENT with a permanently
 * empty participant table: {@code importParticipants()} only ever ran once, at the moment
 * {@code select()} first proposed the chat. If the remote participant list was wrong or empty
 * at that exact moment (in practice: the private-chat listing bug fixed alongside this), there
 * was no way back to a correct snapshot short of resubmitting Step 1 from scratch --
 * {@code ChatSyncService.sync()} refuses to run at all before the chat is ACTIVE, which
 * incomplete consent coverage can never let it reach. {@link io.tgmcp.ui.ChatController#chat}
 * now re-imports participants on every GET while a chat is still AWAITING_CONSENT, so simply
 * reopening the page recovers.
 */
@AutoConfigureMockMvc
class ChatParticipantRefreshTests extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    /** Spied only to control listParticipants(); everything else still runs the real mock logic. */
    @MockitoSpyBean
    private TelegramGateway gateway;

    @Autowired
    private ChatParticipantRepository participantRepository;

    @Test
    void reopeningAnAwaitingConsentChatPicksUpParticipantsMissedAtSelectTime() throws Exception {
        Mockito.when(this.gateway.kind()).thenReturn(TelegramAdapterKind.TDLIB);
        // First call (made by select() below) returns nothing -- the reported bug. Every call
        // after that returns the real participant, as if the underlying fix had since landed.
        Mockito.doReturn(RemoteMembership.complete(List.<RemoteParticipant>of()))
                .doReturn(RemoteMembership.complete(List.of(new RemoteParticipant(555L, "Farrukh Gulomov", false))))
                .when(this.gateway).listParticipants(Mockito.any(), Mockito.eq(SyntheticDataset.ENGINEERING_CHAT));

        Fixtures.Team team = this.fixtures.team("participant-refresh");
        Chat chat = this.fixtures.chat(team, SyntheticDataset.ENGINEERING_CHAT, "Refresh chat", "refresh_chat");

        MockHttpSession session = signInViaTelegramCode(this.mockMvc, team.user().email(),
                team.user().telegramUserId());
        assertThat(session).isNotNull();

        this.mockMvc.perform(post("/chats/" + chat.id() + "/select").session(session).with(csrf())
                        .param("purpose", "Answer questions about team decisions")
                        .param("providers", McpScopes.ANTHROPIC_CLAUDE)
                        .param("approvedFrom", "2026-01-01")
                        .param("approvedTo", "2026-12-31")
                        .param("retentionDays", "365"))
                .andExpect(status().is3xxRedirection());

        // Simply reopening the page re-imports the participant select() missed.
        this.mockMvc.perform(get("/chats/" + chat.id()).session(session))
                .andExpect(status().isOk());

        assertThat(this.participantRepository.findActive(team.tenant().id(), chat.id()))
                .extracting(io.tgmcp.chat.ChatParticipant::displayName)
                .contains("Farrukh Gulomov");
        Mockito.verify(this.gateway, Mockito.times(2))
                .listParticipants(Mockito.any(), Mockito.eq(SyntheticDataset.ENGINEERING_CHAT));
    }

    /**
     * pause() has no precondition on the current state -- an operator can pause a chat that
     * was never activated, straight out of AWAITING_CONSENT. Real-account testing hit exactly
     * that: a chat paused by mistake before ever recording consent stopped refreshing its
     * participant list on reopen, because the fix above only covered AWAITING_CONSENT.
     */
    @Test
    void reopeningAPausedChatThatWasNeverActivatedAlsoRefreshesParticipants() throws Exception {
        Mockito.when(this.gateway.kind()).thenReturn(TelegramAdapterKind.TDLIB);
        Mockito.doReturn(RemoteMembership.complete(List.<RemoteParticipant>of()))
                .doReturn(RemoteMembership.complete(List.of(new RemoteParticipant(555L, "Farrukh Gulomov", false))))
                .when(this.gateway).listParticipants(Mockito.any(), Mockito.eq(SyntheticDataset.ENGINEERING_CHAT));

        Fixtures.Team team = this.fixtures.team("participant-refresh-paused");
        Chat chat = this.fixtures.chat(team, SyntheticDataset.ENGINEERING_CHAT, "Refresh chat", "refresh_chat_paused");

        MockHttpSession session = signInViaTelegramCode(this.mockMvc, team.user().email(),
                team.user().telegramUserId());
        assertThat(session).isNotNull();

        this.mockMvc.perform(post("/chats/" + chat.id() + "/select").session(session).with(csrf())
                        .param("purpose", "Answer questions about team decisions")
                        .param("providers", McpScopes.ANTHROPIC_CLAUDE)
                        .param("approvedFrom", "2026-01-01")
                        .param("approvedTo", "2026-12-31")
                        .param("retentionDays", "365"))
                .andExpect(status().is3xxRedirection());

        // Paused straight out of AWAITING_CONSENT -- never activated, no consent recorded.
        this.mockMvc.perform(post("/chats/" + chat.id() + "/pause").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        this.mockMvc.perform(get("/chats/" + chat.id()).session(session))
                .andExpect(status().isOk());

        assertThat(this.participantRepository.findActive(team.tenant().id(), chat.id()))
                .extracting(io.tgmcp.chat.ChatParticipant::displayName)
                .contains("Farrukh Gulomov");
    }
}
