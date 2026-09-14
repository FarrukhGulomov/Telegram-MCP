package io.tgmcp;

import io.tgmcp.chat.Chat;
import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The management UI is available in English (default), Uzbek and Russian, switched by a
 * {@code ?lang=} link that {@link io.tgmcp.config.LocaleConfig} turns into a long-lived cookie
 * (not a session attribute, so it also applies to the public /login page and survives sign-out).
 */
@AutoConfigureMockMvc
class LocalizationTests extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void loginPageDefaultsToEnglishAndSwitchesOnLangParam() throws Exception {
        this.mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Sign in")));

        this.mockMvc.perform(get("/login").param("lang", "uz"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Kirish")))
                .andExpect(cookie().value("TGMCP_LANG", "uz"));

        this.mockMvc.perform(get("/login").param("lang", "ru"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Войти")))
                .andExpect(cookie().value("TGMCP_LANG", "ru"));
    }

    @Test
    void languageCookiePersistsAcrossAuthenticatedPagesAndTranslatesFlashMessages() throws Exception {
        Fixtures.Team team = this.fixtures.signInReadyTeam("locale-team", "owner@locale.invalid",
                this.passwordEncoder.encode("pw"));

        MvcResult signedIn = this.mockMvc.perform(post("/login").with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("TGMCP_LANG", "ru"))
                        .param("username", team.user().email()).param("password", "pw"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) signedIn.getRequest().getSession(false);
        assertThat(session).isNotNull();

        this.mockMvc.perform(get("/").session(session)
                        .cookie(new jakarta.servlet.http.Cookie("TGMCP_LANG", "ru")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Чаты")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Журнал")));

        this.mockMvc.perform(post("/connect/discover").session(session).with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("TGMCP_LANG", "ru")))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("message",
                        org.hamcrest.Matchers.containsString("Обнаружение — это не разрешение")));
    }

    /**
     * Regression coverage for a real user report: after switching to Uzbek or Russian, the
     * status pills and table cells built from Java enum names (chat state, chat type, sync
     * state, provider id, audit outcome/action) kept showing the raw English constant
     * (INACTIVE, SUPERGROUP, ...) instead of translating, because only static template text
     * had been moved to message keys. Chat/audit templates now resolve these dynamically via
     * {@code #{${'prefix.' + value}}}; this asserts both that the translated word appears and
     * that the raw enum name does not leak through untranslated.
     */
    @Test
    void enumBackedStatusLabelsTranslateInsteadOfLeakingRawJavaConstantNames() throws Exception {
        Fixtures.Team team = this.fixtures.team("enum-i18n");
        Chat chat = this.fixtures.chat(team, SyntheticDataset.ENGINEERING_CHAT, "Enum i18n chat", "enum_i18n_chat");

        MvcResult signedIn = this.mockMvc.perform(post("/login").with(csrf())
                        .param("username", team.user().email()).param("password", "pw"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) signedIn.getRequest().getSession(false);
        assertThat(session).isNotNull();

        this.mockMvc.perform(get("/chats/" + chat.id()).session(session).param("lang", "uz"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Faol emas")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Katta guruh")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("INACTIVE"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SUPERGROUP"))));

        this.mockMvc.perform(get("/chats/" + chat.id()).session(session).param("lang", "ru"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Не активен")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Супергруппа")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("INACTIVE"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SUPERGROUP"))));

        // Trigger a real audit event (discovery), then check its action/outcome translate too.
        this.mockMvc.perform(post("/connect/discover").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        this.mockMvc.perform(get("/audit").session(session).param("lang", "ru"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Чат обнаружен")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Разрешено")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("CHAT_DISCOVERED"))));
    }

    /**
     * Security-review finding: ChatActivationService.activate() built its "N participant(s)
     * haven't consented yet" refusal as a literal English sentence, so it never translated no
     * matter the visitor's language. The service now throws just the count and ChatController
     * supplies the (translatable) sentence around it via validation.activateIncomplete.
     */
    @Test
    void activatingWithIncompleteConsentTranslatesTheRefusalMessage() throws Exception {
        Fixtures.Team team = this.fixtures.team("activate-incomplete-i18n");
        Chat chat = this.fixtures.chat(team, SyntheticDataset.ENGINEERING_CHAT, "Incomplete consent chat",
                "incomplete_consent_chat");

        MvcResult signedIn = this.mockMvc.perform(post("/login").with(csrf())
                        .param("username", team.user().email()).param("password", "pw"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) signedIn.getRequest().getSession(false);
        assertThat(session).isNotNull();

        this.mockMvc.perform(post("/chats/" + chat.id() + "/select").session(session).with(csrf())
                        .param("purpose", "Answer questions about team decisions")
                        .param("providers", io.tgmcp.security.McpScopes.ANTHROPIC_CLAUDE)
                        .param("approvedFrom", "2026-01-01")
                        .param("approvedTo", "2026-12-31")
                        .param("retentionDays", "365"))
                .andExpect(status().is3xxRedirection());

        this.mockMvc.perform(post("/chats/" + chat.id() + "/activate").session(session).with(csrf())
                        .param("lang", "uz"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString(
                                "ushbu maqsad, provayderlar doirasi va rozilik versiyasi uchun hali rozilik bermagan"),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("have not consented")))));
    }
}
