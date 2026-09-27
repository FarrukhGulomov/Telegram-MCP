package io.tgmcp.security;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import static org.assertj.core.api.Assertions.assertThat;

class TrustedProviderAugmentingClientRepositoryTests {

    @Test
    void repairsAChatGptClientPersistedBeforeScopeAugmentationWasDeployed() {
        InMemoryRepository persisted = new InMemoryRepository();
        RegisteredClient legacy = client("legacy-chatgpt",
                "https://chatgpt.com/connector/oauth/existing-callback");
        persisted.save(legacy);

        RegisteredClientRepository repository =
                new TrustedProviderAugmentingClientRepository(persisted);

        RegisteredClient recovered = repository.findByClientId("legacy-chatgpt");

        assertThat(recovered).isNotNull();
        assertThat(recovered.getScopes())
                .contains(McpScopes.READ, "openid", McpScopes.SCOPE_OPENAI_CHATGPT)
                .doesNotContain(McpScopes.SCOPE_ANTHROPIC_CLAUDE);
        assertThat(persisted.findByClientId("legacy-chatgpt").getScopes())
                .as("compatibility recovery must not silently rewrite a persisted OAuth client")
                .isEmpty();
    }

    @Test
    void repairsTheSameLegacyClientWhenAuthorizationStorageLooksItUpById() {
        InMemoryRepository persisted = new InMemoryRepository();
        RegisteredClient legacy = client("legacy-chatgpt-by-id",
                "https://chatgpt.com/connector_platform_oauth_redirect");
        persisted.save(legacy);

        RegisteredClient recovered =
                new TrustedProviderAugmentingClientRepository(persisted).findById(legacy.getId());

        assertThat(recovered.getScopes())
                .contains(McpScopes.READ, "openid", McpScopes.SCOPE_OPENAI_CHATGPT);
    }

    @Test
    void neverAddsAProviderScopeToAnOldClientOnAnUntrustedDomain() {
        InMemoryRepository persisted = new InMemoryRepository();
        persisted.save(client("legacy-untrusted", "https://example.com/callback"));

        RegisteredClient recovered =
                new TrustedProviderAugmentingClientRepository(persisted)
                        .findByClientId("legacy-untrusted");

        assertThat(recovered.getScopes())
                .containsExactlyInAnyOrder(McpScopes.READ, "openid");
        assertThat(recovered.getScopes())
                .noneMatch(scope -> scope.startsWith(McpScopes.PROVIDER_PREFIX));
    }

    private static RegisteredClient client(String clientId, String redirectUri) {
        return RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientName(clientId)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(redirectUri)
                .build();
    }

    private static final class InMemoryRepository implements RegisteredClientRepository {

        private final Map<String, RegisteredClient> byId = new LinkedHashMap<>();
        private final Map<String, RegisteredClient> byClientId = new LinkedHashMap<>();

        @Override
        public void save(RegisteredClient registeredClient) {
            this.byId.put(registeredClient.getId(), registeredClient);
            this.byClientId.put(registeredClient.getClientId(), registeredClient);
        }

        @Override
        public RegisteredClient findById(String id) {
            return this.byId.get(id);
        }

        @Override
        public RegisteredClient findByClientId(String clientId) {
            return this.byClientId.get(clientId);
        }
    }
}
