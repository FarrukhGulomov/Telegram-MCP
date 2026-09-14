package io.tgmcp;

import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
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
}
