package io.tgmcp;

import java.util.Base64;

import io.tgmcp.common.KeyMaterial;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A misconfigured key must fail at startup with a message that says what is wrong, not at
 * the first login attempt with {@code InvalidKeyException}.
 */
class KeyMaterialTests {

    private static String base64(int bytes) {
        return Base64.getEncoder().encodeToString(new byte[bytes]);
    }

    @Test
    void derivesAThirtyTwoByteDevelopmentKeyWhenNoneIsConfigured() {
        assertThat(KeyMaterial.load(null, "AES", "session").getEncoded()).hasSize(32);
        assertThat(KeyMaterial.load("  ", "AES", "session").getEncoded()).hasSize(32);
        assertThat(KeyMaterial.isConfigured(null)).isFalse();
    }

    @Test
    void acceptsAThirtyTwoByteAesKey() {
        assertThat(KeyMaterial.load(base64(32), "AES", "session").getEncoded()).hasSize(32);
        assertThat(KeyMaterial.isConfigured(base64(32))).isTrue();
    }

    @Test
    void rejectsAnAesKeyOfTheWrongLength() {
        for (int length : new int[] {16, 31, 33, 37, 64}) {
            assertThatThrownBy(() -> KeyMaterial.load(base64(length), "AES", "session"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("exactly 32 bytes")
                    .hasMessageContaining("session");
        }
    }

    @Test
    void rejectsAShortMacKeyButAllowsALongerOne() {
        assertThatThrownBy(() -> KeyMaterial.load(base64(16), "HmacSHA256", "cursor"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
        assertThat(KeyMaterial.load(base64(64), "HmacSHA256", "cursor").getEncoded()).hasSize(64);
    }

    @Test
    void rejectsSomethingThatIsNotBase64() {
        assertThatThrownBy(() -> KeyMaterial.load("not base64!!!", "AES", "session"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid base64");
    }

    @Test
    void derivesTheSameThirtyTwoByteKeyForTheSameInputsAndADifferentOneForADifferentLabel() {
        String secret = base64(32);

        byte[] first = KeyMaterial.derive(secret, "session", "tdlib-database-encryption-key");
        byte[] second = KeyMaterial.derive(secret, "session", "tdlib-database-encryption-key");
        byte[] differentLabel = KeyMaterial.derive(secret, "session", "some-other-purpose");

        assertThat(first).hasSize(32).isEqualTo(second).isNotEqualTo(differentLabel);
    }

    @Test
    void derivesADifferentKeyForADifferentConfiguredSecret() {
        byte[] secretA = new byte[32];
        byte[] secretB = new byte[32];
        secretB[0] = 1;

        byte[] fromSecretA = KeyMaterial.derive(Base64.getEncoder().encodeToString(secretA), "session",
                "tdlib-database-encryption-key");
        byte[] fromSecretB = KeyMaterial.derive(Base64.getEncoder().encodeToString(secretB), "session",
                "tdlib-database-encryption-key");

        assertThat(fromSecretA).isNotEqualTo(fromSecretB);
    }
}
