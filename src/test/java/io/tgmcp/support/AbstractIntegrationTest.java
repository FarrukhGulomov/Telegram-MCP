package io.tgmcp.support;

import java.time.Clock;

import io.tgmcp.telegram.gateway.LoginMethod;
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
@Import(TestClockConfiguration.class)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected Fixtures fixtures;

    @Autowired
    protected Clock clock;

    @Autowired
    protected TelegramGateway telegramGateway;

    @BeforeEach
    void resetDatabaseAndClock() {
        this.jdbcTemplate.execute("""
                TRUNCATE audit_events, messages, sync_checkpoints, consent_records, chat_participants,
                         chat_activations, chats, telegram_connections, app_users,
                         tenants, oauth2_authorization, oauth2_authorization_consent, oauth2_registered_client
                RESTART IDENTITY CASCADE
                """);
        // Clock first, then the adapter: the synthetic dataset is generated relative to "now".
        mutableClock().set(TestClockConfiguration.START);
        mockGateway().reset();
    }

    protected MutableClock mutableClock() {
        return (MutableClock) this.clock;
    }

    protected MockTelegramGateway mockGateway() {
        return (MockTelegramGateway) this.telegramGateway;
    }

    /**
     * Drives the MOCK adapter's phone -> code -> password -> READY login through the unified
     * auth endpoints and returns the resulting authenticated session. The mock always asks for
     * a 2FA password after the code (see {@code MockTelegramGateway#submitCode}), so every
     * successful login goes through both steps regardless of whether the account has 2FA set.
     */
    protected MockHttpSession signInViaPhoneNumber(MockMvc mockMvc, String phoneNumber) throws Exception {
        MvcResult started = mockMvc.perform(post("/login/start").with(csrf())
                        .param("method", LoginMethod.PHONE_NUMBER.name())
                        .param("phoneNumber", phoneNumber))
                .andReturn();
        MockHttpSession session = (MockHttpSession) started.getRequest().getSession(false);
        mockMvc.perform(post("/login/code").session(session).with(csrf()).param("code", "00000")).andReturn();
        MvcResult finished = mockMvc.perform(post("/login/password").session(session).with(csrf())
                        .param("password", "unused"))
                .andReturn();
        return (MockHttpSession) finished.getRequest().getSession(false);
    }
}
