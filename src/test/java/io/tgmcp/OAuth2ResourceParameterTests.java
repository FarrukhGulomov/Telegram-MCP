package io.tgmcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import io.tgmcp.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security-audit finding F18 (resource-parameter half): RFC 8707 lets a client name a specific
 * {@code resource} at {@code /oauth2/authorize}. {@link
 * io.tgmcp.security.AuthorizationServerConfig} now rejects a mismatched one outright with {@code
 * invalid_target} instead of silently ignoring it and issuing a token for this server's own
 * resource regardless. Exercised end to end through the real HTTP authorization endpoint (real
 * client registration, real form login, real PKCE), not just the unit-level validator logic
 * covered by {@code AuthorizationServerConfigTests}.
 */
@AutoConfigureMockMvc
class OAuth2ResourceParameterTests extends AbstractIntegrationTest {

    private static final String REDIRECT_URI = "https://example.com/callback";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String registerClient() throws Exception {
        String requestBody = """
                {
                  "client_name": "resource-param-test-client",
                  "redirect_uris": ["%s"],
                  "grant_types": ["authorization_code", "refresh_token"],
                  "response_types": ["code"],
                  "token_endpoint_auth_method": "none",
                  "scope": "mcp:read"
                }
                """.formatted(REDIRECT_URI);

        MvcResult result = this.mockMvc.perform(post("/oauth2/register")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn();

        var response = MAPPER.readTree(result.getResponse().getContentAsByteArray());
        return response.get("client_id").asText();
    }

    private MockHttpSession signIn(String email, String password) throws Exception {
        MvcResult signedIn = this.mockMvc.perform(post("/login").with(csrf())
                        .param("username", email).param("password", password))
                .andReturn();
        return (MockHttpSession) signedIn.getRequest().getSession(false);
    }

    private static String pkceCodeChallenge(String codeVerifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    /**
     * Builds the raw {@code /oauth2/authorize} query string directly rather than via {@code
     * MockMvcRequestBuilders#param}: {@code .param()} on a GET request does not make the
     * parameter visible the way Spring Authorization Server's own {@code
     * OAuth2AuthorizationCodeRequestAuthenticationConverter} reads it (it fails the request with
     * "invalid_request response_type" even though the parameter is plainly present), whereas a
     * literal query string on the request URI behaves exactly like a real browser request.
     */
    private static String authorizeQuery(String clientId, String resource) throws Exception {
        return "/oauth2/authorize?response_type=code&client_id=" + clientId
                + "&redirect_uri=" + REDIRECT_URI
                + "&scope=mcp:read&state=xyz&code_challenge="
                + pkceCodeChallenge("a-fixed-test-code-verifier-value-1234567890")
                + "&code_challenge_method=S256&resource=" + resource;
    }

    @Test
    void rejectsAnAuthorizeRequestNamingAResourceThisServerDoesNotServe() throws Exception {
        String clientId = registerClient();
        String password = "resource-test-pw-1";
        this.fixtures.signInReadyTeam("f18-resource-1", "resource1@f18.invalid",
                this.passwordEncoder.encode(password), "OWNER");
        MockHttpSession session = signIn("resource1@f18.invalid", password);
        assertThat(session).isNotNull();

        MvcResult result = this.mockMvc.perform(get(authorizeQuery(clientId, "https://not-this-server.example"))
                        .session(session))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        assertThat(location).as("must redirect back to the registered redirect_uri with the error, "
                        + "not silently issue a code for this server's own resource")
                .isNotNull()
                .startsWith(REDIRECT_URI)
                .contains("error=invalid_target");
    }

    private static String extractHiddenInputValue(String html, String inputName) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("name=\"" + inputName + "\" value=\"([^\"]*)\"")
                .matcher(html);
        assertThat(matcher.find()).as("consent page must carry a hidden '" + inputName + "' input").isTrue();
        return matcher.group(1);
    }

    @Test
    void allowsAnAuthorizeRequestNamingThisServersOwnResource() throws Exception {
        String clientId = registerClient();
        String password = "resource-test-pw-2";
        this.fixtures.signInReadyTeam("f18-resource-2", "resource2@f18.invalid",
                this.passwordEncoder.encode(password), "OWNER");
        MockHttpSession session = signIn("resource2@f18.invalid", password);
        assertThat(session).isNotNull();

        // This server's own resource must not be rejected the way a mismatched one is: it
        // reaches the ordinary consent step (a DCR-registered client always requires consent),
        // never an error redirect.
        MvcResult authorizeResult = this.mockMvc.perform(get(authorizeQuery(clientId, "http://localhost:8080/mcp"))
                        .session(session))
                .andExpect(status().isOk())
                .andReturn();
        String consentPage = authorizeResult.getResponse().getContentAsString();
        assertThat(consentPage).contains("Consent required");

        String consentState = extractHiddenInputValue(consentPage, "state");
        MvcResult consentResult = this.mockMvc.perform(post("/oauth2/authorize").session(session)
                        .param("client_id", clientId)
                        .param("state", consentState)
                        .param("scope", "mcp:read"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String location = consentResult.getResponse().getHeader("Location");
        assertThat(location).as("consenting to this server's own resource must complete with a normal "
                        + "authorization code, not an error")
                .isNotNull()
                .startsWith(REDIRECT_URI)
                .doesNotContain("error=")
                .contains("code=");
    }
}
