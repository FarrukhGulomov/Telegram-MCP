package io.tgmcp.support;

import java.time.Clock;

import io.tgmcp.telegram.gateway.TelegramGateway;
import io.tgmcp.telegram.mock.MockTelegramGateway;
import org.junit.jupiter.api.BeforeEach;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Base for tests that need the full application against a real PostgreSQL.
 *
 * <p>PostgreSQL rather than an in-memory substitute on purpose: search is a {@code tsvector}
 * generated column with a GIN index, and keyset pagination uses row-value comparison, so an
 * H2 stand-in would test something the product does not do.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestClockConfiguration.class, TestTelegramBotMessengerConfiguration.class})
public abstract class AbstractIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected Fixtures fixtures;

    @Autowired
    protected Clock clock;

    @Autowired
    protected TelegramGateway telegramGateway;

    @Autowired
    protected FakeTelegramBotMessenger telegramBotMessenger;

    @BeforeEach
    void resetDatabaseAndClock() {
        this.jdbcTemplate.execute("""
                TRUNCATE audit_events, messages, sync_checkpoints, consent_records, chat_participants,
                         chat_activations, chats, telegram_connections, login_verification_codes, app_users,
                         tenants, oauth2_authorization, oauth2_authorization_consent, oauth2_registered_client
                RESTART IDENTITY CASCADE
                """);
        // Clock first, then the adapter: the synthetic dataset is generated relative to "now".
        mutableClock().set(TestClockConfiguration.START);
        mockGateway().reset();
        this.telegramBotMessenger.reset();
    }

    protected MutableClock mutableClock() {
        return (MutableClock) this.clock;
    }

    protected MockTelegramGateway mockGateway() {
        return (MockTelegramGateway) this.telegramGateway;
    }

    /**
     * Drives the whole "sign in with a Telegram code" flow -- there is no password to check any
     * more -- and returns the resulting authenticated session: sends a code to {@code
     * telegramUserId} via {@code identifier} (an email or {@code @username}), reads it back from
     * {@link FakeTelegramBotMessenger#latestCodeFor}, and submits it.
     */
    protected MockHttpSession signInViaTelegramCode(MockMvc mockMvc, String identifier, long telegramUserId)
            throws Exception {
        MvcResult sent = mockMvc.perform(post("/login").with(csrf()).param("identifier", identifier))
                .andReturn();
        MockHttpSession session = (MockHttpSession) sent.getRequest().getSession(false);
        String code = this.telegramBotMessenger.latestCodeFor(telegramUserId);
        MvcResult verified = mockMvc.perform(post("/login/verify").session(session).with(csrf())
                        .param("code", code))
                .andReturn();
        return (MockHttpSession) verified.getRequest().getSession(false);
    }
}
