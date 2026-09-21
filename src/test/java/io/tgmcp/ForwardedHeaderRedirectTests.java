package io.tgmcp;

import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Regression coverage for a real bug found in the Railway test deployment: behind a
 * TLS-terminating proxy that forwards over plain HTTP internally (Railway, Heroku, and
 * similar platforms), Tomcat has no way to know the original request was HTTPS unless told.
 * Without {@code server.forward-headers-strategy=framework}, the post-login redirect —
 * {@code HttpServletResponse.sendRedirect} turns the relative target into an absolute URL
 * using the request's own perceived scheme — comes back as {@code http://...} even though the
 * browser is on an {@code https://} page. The browser follows it, the {@code Secure}-flagged
 * session cookie is never sent over that plain-HTTP hop, and the user bounces straight back to
 * the login page looking like the code was wrong when it was correct all along.
 */
@AutoConfigureMockMvc
class ForwardedHeaderRedirectTests extends AbstractIntegrationTest {

    private static final String EMAIL = "owner@forwarded.invalid";

    @Autowired
    private MockMvc mockMvc;

    private MockHttpSession sendCode(String email) throws Exception {
        MvcResult sent = this.mockMvc.perform(post("/login").with(csrf()).param("identifier", email))
                .andReturn();
        return (MockHttpSession) sent.getRequest().getSession(false);
    }

    @Test
    void buildsAnHttpsRedirectWhenTheProxySaysTheOriginalRequestWasHttps() throws Exception {
        Fixtures.Team team = this.fixtures.signInReadyTeam("forwarded", EMAIL);
        MockHttpSession session = sendCode(EMAIL);
        String code = this.telegramBotMessenger.latestCodeFor(team.user().telegramUserId());

        this.mockMvc.perform(post("/login/verify").session(session).with(csrf())
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "app-production-7109.up.railway.app")
                        .param("code", code))
                .andExpect(result -> {
                    String location = result.getResponse().getHeader("Location");
                    assertThat(location)
                            .as("the post-login redirect must not silently downgrade to http:// "
                                    + "behind a TLS-terminating proxy, or the Secure session "
                                    + "cookie never makes it back and login appears to fail")
                            .startsWith("https://app-production-7109.up.railway.app");
                });
    }

    @Test
    void stillBuildsAPlainRedirectWithNoForwardedHeaders() throws Exception {
        // Local development (curl, no proxy in front) must keep working exactly as before.
        Fixtures.Team team = this.fixtures.signInReadyTeam("noproxy", "owner@noproxy.invalid");
        MockHttpSession session = sendCode("owner@noproxy.invalid");
        String code = this.telegramBotMessenger.latestCodeFor(team.user().telegramUserId());

        this.mockMvc.perform(post("/login/verify").session(session).with(csrf()).param("code", code))
                .andExpect(result -> {
                    String location = result.getResponse().getHeader("Location");
                    assertThat(location).doesNotContain("app-production-7109.up.railway.app");
                });
    }
}
