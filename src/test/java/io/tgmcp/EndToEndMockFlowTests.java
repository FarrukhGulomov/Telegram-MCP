package io.tgmcp;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.modelcontextprotocol.spec.McpSchema;
import io.tgmcp.chat.ActivationState;
import io.tgmcp.chat.Chat;
import io.tgmcp.chat.ChatActivationRepository;
import io.tgmcp.chat.ChatParticipant;
import io.tgmcp.chat.ChatParticipantRepository;
import io.tgmcp.chat.ChatRepository;
import io.tgmcp.mcp.McpToolNames;
import io.tgmcp.security.McpScopes;
import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import io.tgmcp.support.McpTestClient;
import io.tgmcp.support.TestTokens;
import io.tgmcp.telegram.TelegramConnection;
import io.tgmcp.telegram.TelegramConnectionRepository;
import io.tgmcp.telegram.gateway.LoginStage;
import io.tgmcp.telegram.mock.SyntheticDataset;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static io.tgmcp.McpProtocolTests.errorCode;
import static io.tgmcp.McpProtocolTests.items;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole product in one test, driven through the real HTTP endpoints:
 * sign in → connect → select chat → collect consent → activate → sync → search over MCP →
 * revoke → denied.
 */
@AutoConfigureMockMvc
class EndToEndMockFlowTests extends AbstractIntegrationTest {

    private static final String EMAIL = "owner@demo.invalid";
    private static final String PASSWORD = "demo-password";
    private static final String ISSUER = "http://localhost:8080";
    private static final String RESOURCE = "http://localhost:8080/mcp";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestTokens tokens;

    @Autowired
    private ChatRepository chats;

    @Autowired
    private ChatActivationRepository activations;

    @Autowired
    private ChatParticipantRepository participants;

    @Autowired
    private TelegramConnectionRepository connections;

    @LocalServerPort
    private int port;

    @Test
    void walksTheWholeConsentGatedJourney() throws Exception {
        Fixtures.Team team = this.fixtures.signInReadyTeam("demo", EMAIL,
                this.passwordEncoder.encode(PASSWORD));

        // 1. Anonymous users get nothing.
        this.mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        // 2. Wrong password fails; the right one establishes a session.
        this.mockMvc.perform(post("/login").with(csrf())
                        .param("username", EMAIL).param("password", "wrong"))
                .andExpect(redirectedUrl("/login?error"));

        MvcResult signedIn = this.mockMvc.perform(post("/login").with(csrf())
                        .param("username", EMAIL).param("password", PASSWORD))
                .andExpect(redirectedUrl("/"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) signedIn.getRequest().getSession(false);
        assertThat(session).isNotNull();

        // 3. Connect Telegram through the two-step login the mock adapter models.
        this.mockMvc.perform(post("/connect/start").session(session).with(csrf())
                .param("method", "QR_CODE")).andExpect(status().is3xxRedirection());
        this.mockMvc.perform(post("/connect/code").session(session).with(csrf())
                .param("code", "12345")).andExpect(status().is3xxRedirection());
        this.mockMvc.perform(post("/connect/password").session(session).with(csrf())
                .param("password", "any")).andExpect(status().is3xxRedirection());

        TelegramConnection connection = this.connections
                .findByOwner(team.tenant().id(), team.user().id()).orElseThrow();
        assertThat(connection.status()).isEqualTo(LoginStage.READY);

        // 4. Discovery is not authorization: chats become visible, nothing is stored.
        this.mockMvc.perform(post("/connect/discover").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Chat> discovered = this.chats.findByConnection(team.tenant().id(), connection.id());
        assertThat(discovered).hasSize(3);
        assertThat(this.jdbcTemplate.queryForObject("SELECT count(*) FROM messages", Long.class))
                .as("discovery must not ingest anything")
                .isZero();

        Chat engineering = discovered.stream()
                .filter(c -> c.tgChatId() == SyntheticDataset.ENGINEERING_CHAT)
                .findFirst().orElseThrow();

        // 5. Selecting a chat records the processing context and imports participants.
        this.mockMvc.perform(post("/chats/" + engineering.id() + "/select").session(session).with(csrf())
                        .param("purpose", "Answer questions about team decisions")
                        .param("providers", McpScopes.ANTHROPIC_CLAUDE)
                        .param("approvedFrom", "2026-01-01")
                        .param("approvedTo", "2026-12-31")
                        .param("retentionDays", "365"))
                .andExpect(status().is3xxRedirection());

        assertThat(this.activations.find(team.tenant().id(), engineering.id()).orElseThrow().state())
                .isEqualTo(ActivationState.AWAITING_CONSENT);

        // 6. Activation is refused while any participant is uncovered.
        this.mockMvc.perform(post("/chats/" + engineering.id() + "/activate").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(this.activations.find(team.tenant().id(), engineering.id()).orElseThrow().state())
                .isEqualTo(ActivationState.AWAITING_CONSENT);

        // 7. Record consent for every non-bot participant in one bulk submission, matching the
        // management UI's single shared "record consent for everyone" button (one form, one
        // input group per participant, no per-row submit).
        List<ChatParticipant> people = this.participants.findActive(team.tenant().id(), engineering.id());
        assertThat(people).hasSize(5);
        var consentAll = post("/chats/" + engineering.id() + "/consent/all").session(session).with(csrf());
        for (ChatParticipant person : people) {
            if (person.bot()) {
                continue;
            }
            consentAll = consentAll
                    .param("participantTgUserId", String.valueOf(person.tgUserId()))
                    .param("participantDisplayName", person.displayName())
                    .param("method", "DEMO_SYNTHETIC")
                    .param("evidenceRef", "synthetic demo consent");
        }
        this.mockMvc.perform(consentAll).andExpect(status().is3xxRedirection());

        // 8. Now activation succeeds.
        this.mockMvc.perform(post("/chats/" + engineering.id() + "/activate").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(this.activations.find(team.tenant().id(), engineering.id()).orElseThrow().state())
                .isEqualTo(ActivationState.ACTIVE);

        // 9. Sync.
        this.mockMvc.perform(post("/chats/" + engineering.id() + "/sync").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(this.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM messages WHERE chat_id = ?", Long.class, engineering.id()))
                .isEqualTo(30L);

        // 10. Search over real MCP.
        String token = this.tokens.token(ISSUER, RESOURCE, team.tenant().id(), team.user().id(),
                List.of(McpScopes.READ, McpScopes.SCOPE_ANTHROPIC_CLAUDE));
        try (McpTestClient mcp = new McpTestClient(this.port, token)) {
            mcp.initialize();

            assertThat(items(mcp.call(McpToolNames.LIST_ALLOWED_CHATS, Map.of()))).hasSize(1);

            McpSchema.CallToolResult found = mcp.call(McpToolNames.SEARCH_MESSAGES, Map.of(
                    "chat_ids", List.of(engineering.id().toString()),
                    "query", "backpressure",
                    "from", "2026-01-01T00:00:00Z",
                    "to", this.clock.instant().toString()));
            assertThat(found.isError()).isFalse();
            assertThat(items(found)).isNotEmpty();

            // 11. Revoke through the UI.
            this.mockMvc.perform(post("/chats/" + engineering.id() + "/revoke")
                    .session(session).with(csrf())).andExpect(status().is3xxRedirection());

            // 12. The very next MCP call is denied, and the stored copy is gone.
            McpSchema.CallToolResult denied = mcp.call(McpToolNames.SEARCH_MESSAGES, Map.of(
                    "chat_ids", List.of(engineering.id().toString()),
                    "query", "backpressure",
                    "from", "2026-01-01T00:00:00Z",
                    "to", this.clock.instant().toString()));
            assertThat(denied.isError()).isTrue();
            assertThat(errorCode(denied)).isEqualTo("ACCESS_DENIED");
            assertThat(items(mcp.call(McpToolNames.LIST_ALLOWED_CHATS, Map.of()))).isEmpty();
        }

        assertThat(this.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM messages WHERE chat_id = ?", Long.class, engineering.id()))
                .isZero();

        // 13. The journey is on the audit trail, without any message text.
        List<String> actions = this.jdbcTemplate.queryForList(
                "SELECT action FROM audit_events WHERE tenant_id = ? ORDER BY occurred_at",
                String.class, team.tenant().id());
        assertThat(actions).contains("TELEGRAM_CONNECTED", "CHAT_DISCOVERED", "CONSENT_RECORDED",
                "CHAT_ACTIVATED", "SYNC_SUCCEEDED", "MCP_TOOL_CALL", "CHAT_REVOKED");

        String auditJson = String.join(" ", this.jdbcTemplate.queryForList(
                "SELECT detail::text FROM audit_events WHERE tenant_id = ?",
                String.class, team.tenant().id()));
        assertThat(auditJson)
                .as("audit records access metadata, never message content")
                .doesNotContain("backpressure").doesNotContain("ring buffer");
    }

    @Test
    void rejectsAStateChangingPostWithoutACsrfToken() throws Exception {
        Fixtures.Team team = this.fixtures.signInReadyTeam("demo", EMAIL,
                this.passwordEncoder.encode(PASSWORD));
        MvcResult signedIn = this.mockMvc.perform(post("/login").with(csrf())
                .param("username", EMAIL).param("password", PASSWORD)).andReturn();
        MockHttpSession session = (MockHttpSession) signedIn.getRequest().getSession(false);

        this.mockMvc.perform(post("/chats/" + UUID.randomUUID() + "/revoke").session(session))
                .andExpect(status().isForbidden());
    }

    @Test
    void escapesUntrustedChatTitlesRatherThanRenderingThemAsMarkup() throws Exception {
        Fixtures.Team team = this.fixtures.signInReadyTeam("demo", EMAIL,
                this.passwordEncoder.encode(PASSWORD));
        MvcResult signedIn = this.mockMvc.perform(post("/login").with(csrf())
                .param("username", EMAIL).param("password", PASSWORD)).andReturn();
        MockHttpSession session = (MockHttpSession) signedIn.getRequest().getSession(false);

        this.mockMvc.perform(post("/connect/start").session(session).with(csrf())
                .param("method", "QR_CODE"));
        this.mockMvc.perform(post("/connect/code").session(session).with(csrf()).param("code", "1"));
        this.mockMvc.perform(post("/connect/password").session(session).with(csrf())
                .param("password", "1"));
        this.mockMvc.perform(post("/connect/discover").session(session).with(csrf()));

        TelegramConnection connection = this.connections
                .findByOwner(team.tenant().id(), team.user().id()).orElseThrow();
        Chat chat = this.chats.findByConnection(team.tenant().id(), connection.id()).get(0);
        // A chat title is attacker-controlled data, just like message text.
        this.jdbcTemplate.update("UPDATE chats SET title = ? WHERE id = ?",
                "<script>alert('xss')</script>", chat.id());

        this.mockMvc.perform(get("/").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("<script>alert('xss')</script>"))))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("&lt;script&gt;")));
    }

    @Test
    void sendsAConservativeContentSecurityPolicyWithTheManagementUi() throws Exception {
        this.mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Content-Security-Policy",
                                org.hamcrest.Matchers.containsString("frame-ancestors 'none'")));
    }
}
