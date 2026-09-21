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
import io.tgmcp.support.Fixtures;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression coverage for a real connection failure: the actual ChatGPT MCP connector (not the
 * synthetic "declares mcp:read explicitly" client every other OAuth test here registers) sends a
 * Dynamic Client Registration request with no {@code scope} field at all, then, at {@code
 * /oauth2/authorize}, requests every scope this server's discovery metadata advertises --
 * {@code openid mcp:read provider:anthropic_claude provider:openai_chatgpt} -- rather than only
 * the one provider scope it is entitled to. Confirmed from a real Railway deployment's logs:
 * registration succeeded (201) but every subsequent {@code /oauth2/authorize} attempt failed
 * with {@code invalid_scope}, so the connector could never complete a first connection.
 *
 * <p>{@link io.tgmcp.security.TrustedProviderAugmentingClientRepository} grants a self-registered
 * client with no declared scope a safe default ({@code mcp:read} + {@code openid}), and -- only
 * when a redirect URI resolves to a domain this deployment trusts (finding F09 is preserved: the
 * provider identity still never comes from what the client asserts about itself, only from which
 * domain can actually receive the redirect) -- the matching {@code provider:*} scope too. {@link
 * io.tgmcp.security.AuthorizationServerConfig}'s authorize-request converter then restricts an
 * over-broad scope request to the intersection with what the client actually holds instead of
 * rejecting it outright.
 */
@AutoConfigureMockMvc
class TrustedProviderConnectorTests extends AbstractIntegrationTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    private String register(String clientName, String redirectUri, String scopeOrNull) throws Exception {
        String scopeField = scopeOrNull == null ? "" : ",\n  \"scope\": \"" + scopeOrNull + "\"";
        String requestBody = """
                {
                  "client_name": "%s",
                  "redirect_uris": ["%s"],
                  "grant_types": ["authorization_code", "refresh_token"],
                  "response_types": ["code"],
                  "token_endpoint_auth_method": "none"%s
                }
                """.formatted(clientName, redirectUri, scopeField);

        MvcResult result = this.mockMvc.perform(post("/oauth2/register")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn();

        return MAPPER.readTree(result.getResponse().getContentAsByteArray()).get("client_id").asText();
    }

    private static String pkceCodeChallenge(String codeVerifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static String authorizeQuery(String clientId, String redirectUri, String scope) throws Exception {
        // Raw, unencoded concatenation on purpose, matching OAuth2ResourceParameterTests: MockMvc's
        // get(String) treats this as a literal query string exactly like a real browser request,
        // so encoding here would double-encode it.
        String query = "/oauth2/authorize?response_type=code&client_id=" + clientId
                + "&redirect_uri=" + redirectUri
                + "&state=xyz&code_challenge="
                + pkceCodeChallenge("a-fixed-test-code-verifier-value-1234567890")
                + "&code_challenge_method=S256";
        return scope == null ? query : query + "&scope=" + scope;
    }

    private static String extractHiddenInputValue(String html, String inputName) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("name=\"" + inputName + "\" value=\"([^\"]*)\"")
                .matcher(html);
        assertThat(matcher.find()).as("consent page must carry a hidden '" + inputName + "' input").isTrue();
        return matcher.group(1);
    }

    private static String extractAuthorizationCode(String redirectLocation) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[?&]code=([^&]+)")
                .matcher(redirectLocation);
        assertThat(matcher.find()).as("a successful consent must redirect back with an authorization code")
                .isTrue();
        return matcher.group(1);
    }

    @Test
    void selfRegisteredClientWithNoDeclaredScopeCanStillCompleteAuthorization() throws Exception {
        String redirectUri = "https://example.com/callback";
        String clientId = register("generic-mcp-client", redirectUri, null);
        Fixtures.Team team = this.fixtures.signInReadyTeam("connector-1", "connector1@test.invalid", "OWNER");
        MockHttpSession session = signInViaPhoneNumber(this.mockMvc, team.user().phoneNumber());
        assertThat(session).isNotNull();

        MvcResult authorizeResult = this.mockMvc.perform(get(authorizeQuery(clientId, redirectUri, "mcp:read"))
                        .session(session))
                .andExpect(status().isOk())
                .andReturn();
        String consentPage = authorizeResult.getResponse().getContentAsString();
        assertThat(consentPage).contains("mcp:read");

        String state = extractHiddenInputValue(consentPage, "state");
        MvcResult consentResult = this.mockMvc.perform(post("/oauth2/authorize").session(session)
                        .param("client_id", clientId)
                        .param("state", state)
                        .param("scope", "mcp:read"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(consentResult.getResponse().getHeader("Location"))
                .as("a client that declared no scope at registration must still be able to complete "
                        + "authorization with the mcp:read default, not be stuck with an unusable empty scope")
                .startsWith(redirectUri)
                .doesNotContain("error=")
                .contains("code=");
    }

    @Test
    void chatgptConnectorRequestingEveryAdvertisedScopeIsRestrictedToItsOwnProviderScope() throws Exception {
        // The exact shape of the real registration request and redirect URI a live ChatGPT
        // connector sent (from Railway production logs): no scope declared, and a per-connection
        // redirect path under chatgpt.com rather than a single fixed callback URL.
        String redirectUri = "https://chatgpt.com/connector/oauth/yqQjVKRbdOiv";
        String clientId = register("ChatGPT", redirectUri, null);
        Fixtures.Team team = this.fixtures.signInReadyTeam("connector-2", "connector2@test.invalid", "OWNER");
        MockHttpSession session = signInViaPhoneNumber(this.mockMvc, team.user().phoneNumber());
        assertThat(session).isNotNull();

        // Exactly what the real connector requested: every scope this server's own discovery
        // metadata advertises, including the other provider's.
        String requestedScope = "openid mcp:read provider:anthropic_claude provider:openai_chatgpt";
        MvcResult authorizeResult = this.mockMvc.perform(get(authorizeQuery(clientId, redirectUri, requestedScope))
                        .session(session))
                .andExpect(status().isOk())
                .andReturn();
        String consentPage = authorizeResult.getResponse().getContentAsString();
        assertThat(consentPage)
                .as("the redirect_uri under chatgpt.com must have earned exactly the ChatGPT provider scope")
                .contains("provider:openai_chatgpt")
                .as("but never the other provider's scope, no matter what the client itself requested")
                .doesNotContain("provider:anthropic_claude");

        String state = extractHiddenInputValue(consentPage, "state");
        MvcResult consentResult = this.mockMvc.perform(post("/oauth2/authorize").session(session)
                        .param("client_id", clientId)
                        .param("state", state)
                        .param("scope", "mcp:read")
                        .param("scope", "openid")
                        .param("scope", "provider:openai_chatgpt"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(consentResult.getResponse().getHeader("Location"))
                .as("the real-world ChatGPT connector request shape must be able to complete authorization")
                .startsWith(redirectUri)
                .doesNotContain("error=")
                .contains("code=");
    }

    @Test
    void aSelfRegisteredClientOnAnUntrustedDomainNeverEarnsAProviderScope() throws Exception {
        String redirectUri = "https://not-a-real-provider.example/callback";
        String clientId = register("untrusted-client", redirectUri, null);
        Fixtures.Team team = this.fixtures.signInReadyTeam("connector-3", "connector3@test.invalid", "OWNER");
        MockHttpSession session = signInViaPhoneNumber(this.mockMvc, team.user().phoneNumber());
        assertThat(session).isNotNull();

        String requestedScope = "mcp:read provider:anthropic_claude provider:openai_chatgpt";
        MvcResult authorizeResult = this.mockMvc.perform(get(authorizeQuery(clientId, redirectUri, requestedScope))
                        .session(session))
                .andExpect(status().isOk())
                .andReturn();
        String consentPage = authorizeResult.getResponse().getContentAsString();
        assertThat(consentPage)
                .as("an untrusted domain asking for a provider identity for itself must never receive one "
                        + "(security-audit finding F09), even when it is not the one that self-declared it "
                        + "at registration time")
                .contains("mcp:read")
                .doesNotContain("provider:anthropic_claude")
                .doesNotContain("provider:openai_chatgpt");
    }

    /**
     * Regression coverage for a second real connection failure, found immediately after the
     * scope fix above let a connector reach this far for the first time: no test anywhere in
     * this suite had ever driven a real {@code /oauth2/token} exchange end to end, so nothing
     * caught that {@link AuthorizationServerConfig#jwtCustomizer} put the audience claim in as
     * {@code List.of(...)} -- a JDK-internal immutable list type Spring Security's Jackson
     * {@code PolymorphicTypeValidator} refuses to deserialize. The first {@code /oauth2/token}
     * call only ever *writes* that claims map to the database as part of the persisted {@code
     * OAuth2Authorization} row; a real Railway deployment's logs showed the exact failure only
     * on the connector's very next {@code POST /oauth2/token} two seconds later, which has to
     * *read* that row back -- confirmed by an {@code InvalidTypeIdException} naming exactly
     * {@code java.util.ImmutableCollections$List12}. This client (like every client this server
     * ever registers -- {@code token_endpoint_auth_method: none}, PKCE, no client secret) is
     * never issued a refresh token, so the connector's second call cannot have been a refresh
     * grant; the same shape (a second {@code /oauth2/token} call against an authorization
     * already used once) is reproduced here by reusing the same authorization code, which reads
     * back the very row the first exchange just wrote the poisoned claims into.
     */
    @Test
    void anIssuedTokenSurvivesTheAuthorizationBeingReReadFromStorageAfterwards() throws Exception {
        String redirectUri = "https://example.com/callback";
        String clientId = register("generic-mcp-client-refresh", redirectUri, null);
        Fixtures.Team team = this.fixtures.signInReadyTeam("connector-5", "connector5@test.invalid", "OWNER");
        MockHttpSession session = signInViaPhoneNumber(this.mockMvc, team.user().phoneNumber());
        assertThat(session).isNotNull();

        MvcResult authorizeResult = this.mockMvc.perform(get(authorizeQuery(clientId, redirectUri, "mcp:read"))
                        .session(session))
                .andExpect(status().isOk())
                .andReturn();
        String state = extractHiddenInputValue(authorizeResult.getResponse().getContentAsString(), "state");
        MvcResult consentResult = this.mockMvc.perform(post("/oauth2/authorize").session(session)
                        .param("client_id", clientId)
                        .param("state", state)
                        .param("scope", "mcp:read"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String code = extractAuthorizationCode(consentResult.getResponse().getHeader("Location"));

        this.mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", redirectUri)
                        .param("client_id", clientId)
                        .param("code_verifier", "a-fixed-test-code-verifier-value-1234567890"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.access_token").exists());

        // The exact real-world failure: this second call must re-read the very
        // OAuth2Authorization row the first call just wrote, including the access token's
        // persisted JWT claims -- reusing a spent code is what real Spring Authorization Server
        // does to detect a replay, so it must cleanly reject it (invalid_grant), never throw a
        // deserialization exception (which surfaced to the client as a bare redirect instead of
        // any JSON response at all).
        MvcResult reuseResult = this.mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", redirectUri)
                        .param("client_id", clientId)
                        .param("code_verifier", "a-fixed-test-code-verifier-value-1234567890"))
                .andReturn();
        assertThat(reuseResult.getResponse().getStatus())
                .as("reusing a spent authorization code must cleanly fail as invalid_grant, not throw")
                .isEqualTo(400);
        var errorJson = MAPPER.readTree(reuseResult.getResponse().getContentAsByteArray());
        assertThat(errorJson.get("error").asText()).isEqualTo("invalid_grant");
    }
}
