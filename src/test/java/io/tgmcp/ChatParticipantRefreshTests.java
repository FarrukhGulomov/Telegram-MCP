package io.tgmcp;

import java.util.List;

import io.tgmcp.chat.Chat;
import io.tgmcp.security.McpScopes;
import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
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
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

    @Test
    void reopeningAnAwaitingConsentChatPicksUpParticipantsMissedAtSelectTime() throws Exception {
        Mockito.when(this.gateway.kind()).thenReturn(TelegramAdapterKind.TDLIB);
        // First call (made by select() below) returns nothing -- the reported bug. Every call
        // after that returns the real participant, as if the underlying fix had since landed.
        Mockito.doReturn(List.<RemoteParticipant>of())
                .doReturn(List.of(new RemoteParticipant(555L, "Farrukh Gulomov", false)))
                .when(this.gateway).listParticipants(Mockito.any(), Mockito.eq(SyntheticDataset.ENGINEERING_CHAT));

        Fixtures.Team team = this.fixtures.team("participant-refresh");
        Chat chat = this.fixtures.chat(team, SyntheticDataset.ENGINEERING_CHAT, "Refresh chat", "refresh_chat");

        MvcResult signedIn = this.mockMvc.perform(post("/login").with(csrf())
                        .param("username", team.user().email()).param("password", "pw"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) signedIn.getRequest().getSession(false);
        assertThat(session).isNotNull();

        this.mockMvc.perform(post("/chats/" + chat.id() + "/select").session(session).with(csrf())
                        .param("purpose", "Answer questions about team decisions")
                        .param("providers", McpScopes.ANTHROPIC_CLAUDE)
                        .param("approvedFrom", "2026-01-01")
                        .param("approvedTo", "2026-12-31")
                        .param("retentionDays", "365"))
                .andExpect(status().is3xxRedirection());

        // Simply reopening the page re-imports and shows the participant select() missed.
        this.mockMvc.perform(get("/chats/" + chat.id()).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Farrukh Gulomov")));

        Mockito.verify(this.gateway, Mockito.times(2))
                .listParticipants(Mockito.any(), Mockito.eq(SyntheticDataset.ENGINEERING_CHAT));
    }
}
