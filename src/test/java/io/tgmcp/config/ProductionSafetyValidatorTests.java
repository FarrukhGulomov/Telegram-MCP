package io.tgmcp.config;

import java.util.List;

import io.tgmcp.telegram.gateway.TelegramAdapterKind;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Security-audit finding F10: real Telegram account access must refuse to start alongside any
 * setting that only makes sense for a local demo. Exercises {@link ProductionSafetyValidator}
 * directly (a plain constructor call, no Spring context) against every unsafe combination the
 * audit named, plus the one combination that must be allowed to start.
 */
class ProductionSafetyValidatorTests {

    private static final String KEY = "a-non-blank-value-is-enough-for-this-check";

    private static AppProperties properties(boolean realAccountAccessAuthorized, boolean seedSyntheticData,
                                            String sessionEncryptionKey, String cursorSigningKey) {
        return properties(realAccountAccessAuthorized, seedSyntheticData, sessionEncryptionKey, cursorSigningKey,
                KEY);
    }

    private static AppProperties properties(boolean realAccountAccessAuthorized, boolean seedSyntheticData,
                                            String sessionEncryptionKey, String cursorSigningKey,
                                            String oauthRsaPrivateKey) {
        // Every test other than the ones specifically about SEC-07 wants that check to be a
        // non-issue, so it can focus on its own one setting; acknowledged=true here.
        return properties(realAccountAccessAuthorized, seedSyntheticData, sessionEncryptionKey, cursorSigningKey,
                oauthRsaPrivateKey, true);
    }

    private static AppProperties properties(boolean realAccountAccessAuthorized, boolean seedSyntheticData,
                                            String sessionEncryptionKey, String cursorSigningKey,
                                            String oauthRsaPrivateKey,
                                            boolean acknowledgeMissingEditDeletionPipeline) {
        AppProperties.Telegram telegram = new AppProperties.Telegram(TelegramAdapterKind.TDLIB,
                "1", "hash", "./tdlib-data", realAccountAccessAuthorized,
                acknowledgeMissingEditDeletionPipeline);
        AppProperties.Secrets secrets = new AppProperties.Secrets(sessionEncryptionKey, "key-id", cursorSigningKey,
                oauthRsaPrivateKey);
        AppProperties.Demo demo = new AppProperties.Demo(seedSyntheticData, "demo", "Demo", "owner@demo.invalid",
                "pw", List.of());
        return new AppProperties(telegram, null, null, null, secrets, demo, null);
    }

    @Test
    void allowsMockOrUnauthorizedRealModeRegardlessOfEveryOtherUnsafeSetting() {
        AppProperties dangerousExceptForAuthorization = properties(false, true, null, null);

        assertThatCode(() -> new ProductionSafetyValidator(dangerousExceptForAuthorization,
                "http://localhost:8080", "http://localhost:8080", false))
                .as("nothing here matters until real account access is actually authorized")
                .doesNotThrowAnyException();
    }

    @Test
    void refusesRealAccountAccessAlongsideSyntheticDemoSeeding() {
        AppProperties properties = properties(true, true, KEY, KEY);

        assertThatThrownBy(() -> new ProductionSafetyValidator(properties,
                "https://mcp.example.com", "https://mcp.example.com", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seed-synthetic-data");
    }

    @Test
    void refusesRealAccountAccessWithoutASessionEncryptionKey() {
        AppProperties properties = properties(true, false, null, KEY);

        assertThatThrownBy(() -> new ProductionSafetyValidator(properties,
                "https://mcp.example.com", "https://mcp.example.com", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("session-encryption-key");
    }

    @Test
    void refusesRealAccountAccessWithoutACursorSigningKey() {
        AppProperties properties = properties(true, false, KEY, null);

        assertThatThrownBy(() -> new ProductionSafetyValidator(properties,
                "https://mcp.example.com", "https://mcp.example.com", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cursor-signing-key");
    }

    @Test
    void refusesRealAccountAccessWithoutAnOauthRsaPrivateKey() {
        AppProperties properties = properties(true, false, KEY, KEY, null);

        assertThatThrownBy(() -> new ProductionSafetyValidator(properties,
                "https://mcp.example.com", "https://mcp.example.com", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("oauth-rsa-private-key");
    }

    @Test
    void refusesRealAccountAccessWithANonHttpsIssuer() {
        AppProperties properties = properties(true, false, KEY, KEY);

        assertThatThrownBy(() -> new ProductionSafetyValidator(properties,
                "http://mcp.example.com", "https://mcp.example.com", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("oauth.issuer");
    }

    @Test
    void refusesRealAccountAccessWithANonHttpsPublicBaseUrl() {
        AppProperties properties = properties(true, false, KEY, KEY);

        assertThatThrownBy(() -> new ProductionSafetyValidator(properties,
                "https://mcp.example.com", "http://mcp.example.com", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("public-base-url");
    }

    @Test
    void refusesRealAccountAccessWithoutSecureCookies() {
        AppProperties properties = properties(true, false, KEY, KEY);

        assertThatThrownBy(() -> new ProductionSafetyValidator(properties,
                "https://mcp.example.com", "https://mcp.example.com", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cookie.secure");
    }

    @Test
    void reportsEveryUnsafeSettingAtOnceRatherThanStoppingAtTheFirst() {
        AppProperties properties = properties(true, true, null, null, null);

        assertThatThrownBy(() -> new ProductionSafetyValidator(properties,
                "http://mcp.example.com", "http://mcp.example.com", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seed-synthetic-data")
                .hasMessageContaining("session-encryption-key")
                .hasMessageContaining("cursor-signing-key")
                .hasMessageContaining("oauth-rsa-private-key")
                .hasMessageContaining("oauth.issuer")
                .hasMessageContaining("public-base-url")
                .hasMessageContaining("cookie.secure");
    }

    /**
     * Security-audit finding SEC-07: F08 only ever logged the TDLIB adapter's missing
     * edit/deletion pipeline as a warning -- a deployment could authorize real-account access
     * against it and start anyway. This must now refuse to start unless the operator has
     * explicitly acknowledged the gap.
     */
    @Test
    void refusesRealAccountAccessAgainstAnAdapterWithNoLiveRevisionPipelineUnlessAcknowledged() {
        AppProperties properties = properties(true, false, KEY, KEY, KEY, false);

        assertThatThrownBy(() -> new ProductionSafetyValidator(properties,
                "https://mcp.example.com", "https://mcp.example.com", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no live edit/deletion pipeline")
                .hasMessageContaining("acknowledge-missing-edit-deletion-pipeline");
    }

    @Test
    void allowsRealAccountAccessAgainstAnAdapterWithNoLiveRevisionPipelineOnceAcknowledged() {
        AppProperties properties = properties(true, false, KEY, KEY, KEY, true);

        assertThatCode(() -> new ProductionSafetyValidator(properties,
                "https://mcp.example.com", "https://mcp.example.com", true))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsRealAccountAccessWhenEverySafetySettingIsCorrect() {
        AppProperties properties = properties(true, false, KEY, KEY);

        assertThatCode(() -> new ProductionSafetyValidator(properties,
                "https://mcp.example.com", "https://mcp.example.com", true))
                .doesNotThrowAnyException();
    }

    // Sanity check that the fixture builder itself produces the shape the validator expects.
    @Test
    void fixtureBuilderProducesTheExpectedTelegramConfig() {
        AppProperties properties = properties(true, false, KEY, KEY);
        assertThat(properties.telegram().realAccountAccessAuthorized()).isTrue();
        assertThat(properties.demo().seedSyntheticData()).isFalse();
    }
}
