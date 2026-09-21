package io.tgmcp;

import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import io.tgmcp.telegram.gateway.LoginMethod;
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

    /**
     * Starts a phone-number login and drives it up to (but not through) the final {@code
     * /login/password} step -- the mock adapter always asks for a 2FA password after the code,
     * so that final step is where the post-login redirect this test cares about is built.
     */
    private MockHttpSession startLoginToPasswordStep(String phoneNumber) throws Exception {
        MvcResult started = this.mockMvc.perform(post("/login/start").with(csrf())
                        .param("method", LoginMethod.PHONE_NUMBER.name())
                        .param("phoneNumber", phoneNumber))
                .andReturn();
        MockHttpSession session = (MockHttpSession) started.getRequest().getSession(false);
        this.mockMvc.perform(post("/login/code").session(session).with(csrf()).param("code", "00000"));
        return session;
    }

    @Test
    void buildsAnHttpsRedirectWhenTheProxySaysTheOriginalRequestWasHttps() throws Exception {
        Fixtures.Team team = this.fixtures.signInReadyTeam("forwarded", EMAIL);
        MockHttpSession session = startLoginToPasswordStep(team.user().phoneNumber());

        this.mockMvc.perform(post("/login/password").session(session).with(csrf())
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "app-production-7109.up.railway.app")
                        .param("password", "unused"))
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
        MockHttpSession session = startLoginToPasswordStep(team.user().phoneNumber());

        this.mockMvc.perform(post("/login/password").session(session).with(csrf()).param("password", "unused"))
                .andExpect(result -> {
                    String location = result.getResponse().getHeader("Location");
                    assertThat(location).doesNotContain("app-production-7109.up.railway.app");
                });
    }
}
