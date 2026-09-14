package io.tgmcp.security;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;

import com.nimbusds.jose.jwk.RSAKey;
import io.tgmcp.config.AppProperties;
import io.tgmcp.telegram.gateway.TelegramAdapterKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Security-audit finding F18: without a persistent key, a fresh RSA key pair is generated on
 * every startup, invalidating every outstanding OAuth token on restart and giving each replica
 * of a multi-instance deployment its own, mutually incompatible key. Exercises the key-loading
 * logic directly (no Spring context, no native library) against both branches.
 */
class AuthorizationServerConfigTests {

    private static final AuthorizationServerConfig CONFIG = new AuthorizationServerConfig();

    private static AppProperties propertiesWithOauthKey(String oauthRsaPrivateKey) {
        AppProperties.Telegram telegram = new AppProperties.Telegram(TelegramAdapterKind.MOCK,
                null, null, "./tdlib", false);
        AppProperties.Secrets secrets = new AppProperties.Secrets(null, "test", null, oauthRsaPrivateKey);
        AppProperties.Demo demo = new AppProperties.Demo(false, "demo", "Demo", "a@b.invalid", "pw", List.of());
        return new AppProperties(telegram, null, null, null, secrets, demo);
    }

    private static String freshBase64Pkcs8Key() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        return Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
    }

    @Test
    void loadingAConfiguredKeyTwiceProducesTheSameKeyMaterial() throws Exception {
        String base64Key = freshBase64Pkcs8Key();

        RSAKey first = CONFIG.loadPersistentRsaKey(base64Key, "test-key");
        RSAKey second = CONFIG.loadPersistentRsaKey(base64Key, "test-key");

        assertThat(first.getModulus()).isEqualTo(second.getModulus());
        assertThat(first.getPrivateExponent()).isEqualTo(second.getPrivateExponent());
        assertThat(first.getKeyID()).isEqualTo("test-key");
    }

    @Test
    void generatingAnEphemeralKeyTwiceProducesDifferentKeyMaterial() {
        // The exact scenario the fix closes: with no configured key, a restart (or a second
        // replica) would mint tokens under a key nothing else could ever validate.
        RSAKey first = CONFIG.generateEphemeralRsaKey("dev");
        RSAKey second = CONFIG.generateEphemeralRsaKey("dev");

        assertThat(first.getModulus()).isNotEqualTo(second.getModulus());
    }

    @Test
    void jwkSourceUsesThePersistentKeyWhenConfigured() throws Exception {
        String base64Key = freshBase64Pkcs8Key();
        AppProperties properties = propertiesWithOauthKey(base64Key);

        var source1 = CONFIG.jwkSource("k", properties);
        var source2 = CONFIG.jwkSource("k", properties);

        RSAKey key1 = (RSAKey) source1.get(new com.nimbusds.jose.jwk.JWKSelector(
                new com.nimbusds.jose.jwk.JWKMatcher.Builder().build()), null).get(0);
        RSAKey key2 = (RSAKey) source2.get(new com.nimbusds.jose.jwk.JWKSelector(
                new com.nimbusds.jose.jwk.JWKMatcher.Builder().build()), null).get(0);
        assertThat(key1.getModulus()).isEqualTo(key2.getModulus());
    }

    @Test
    void rejectsAnUnparseablePersistentKeyLoudlyRatherThanSilentlyFallingBackToEphemeral() {
        assertThatThrownBy(() -> CONFIG.loadPersistentRsaKey("not-a-valid-key", "test-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("oauth-rsa-private-key");
    }
}
