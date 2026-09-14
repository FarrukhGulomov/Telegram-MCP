package io.tgmcp;

import io.tgmcp.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * RFC 7591 dynamic client registration must accept an anonymous request: a brand new MCP
 * client (Claude, ChatGPT) has no credentials yet, so requiring prior authentication on this
 * one authorization-server endpoint makes automatic registration impossible.
 *
 * <p>Regression coverage for a real bug found in a Railway test deployment: Spring
 * Authorization Server's client-registration endpoint defaults {@code openRegistrationAllowed}
 * to false (rejecting every anonymous request with {@code invalid_token}) and its default
 * scope validator refuses any request that sets {@code scope} at all — which every real MCP
 * client does, since it declares the scope it intends to use. {@link
 * io.tgmcp.security.AuthorizationServerConfig} enables open registration and swaps in Spring's
 * own {@code SIMPLE_SCOPE_VALIDATOR} to allow it. Verified manually end to end against a
 * running instance (see the session record): registration now returns 201 with the requested
 * scope honoured, and {@code /oauth2/authorize} — untouched by this fix — still redirects an
 * anonymous browser request to {@code /login} exactly as before.
 */
@AutoConfigureMockMvc
class DynamicClientRegistrationTests extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void acceptsAnAnonymousRegistrationRequestAndHonoursItsDeclaredScope() throws Exception {
        String requestBody = """
                {
                  "client_name": "test-mcp-client",
                  "redirect_uris": ["https://example.com/callback"],
                  "grant_types": ["authorization_code", "refresh_token"],
                  "response_types": ["code"],
                  "token_endpoint_auth_method": "none",
                  "scope": "mcp:read provider:anthropic_claude"
                }
                """;

        this.mockMvc.perform(post("/oauth2/register")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                // The regression was a 302 redirect to /login. A successful registration is
                // 201; what matters most here is that it is emphatically not a redirect.
                .andExpect(result -> {
                    int responseStatus = result.getResponse().getStatus();
                    if (responseStatus >= 300 && responseStatus < 400) {
                        throw new AssertionError("Anonymous client registration was redirected "
                                + "(status " + responseStatus + ", Location: "
                                + result.getResponse().getHeader("Location")
                                + ") instead of being handled by the registration endpoint.");
                    }
                })
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.scope", org.hamcrest.Matchers.containsString("provider:anthropic_claude")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.scope", org.hamcrest.Matchers.containsString("mcp:read")));
    }

    @Test
    void rejectsAnAuthorizationCodeClientWithNoRedirectUriTheSamePlainWayAsBefore() throws Exception {
        // The relaxed scope validator must not have loosened anything else: redirect_uris is
        // still required for the authorization_code grant, exactly as Spring's own default
        // redirect-uri validator (left untouched by this fix) has always required.
        String requestBody = """
                {
                  "client_name": "no-redirect-uri",
                  "grant_types": ["authorization_code"],
                  "response_types": ["code"],
                  "token_endpoint_auth_method": "none"
                }
                """;

        this.mockMvc.perform(post("/oauth2/register")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().is4xxClientError());
    }
}
