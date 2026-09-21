package io.tgmcp.telegram.session;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import io.tgmcp.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Security-audit finding SEC-R15: an AES-GCM envelope's authentication tag proves its bytes
 * were not altered, but says nothing about which tenant/connection row it belongs to unless
 * that context is bound into the additional authenticated data (AAD). These tests pin that
 * binding: the same plaintext round-trips only under the exact tenant/connection it was
 * encrypted for, and a ciphertext moved to any other context fails closed rather than
 * decrypting into something else's session material.
 */
class SessionCipherTests {

    private final UUID tenantA = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID tenantB = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID connectionA = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final UUID connectionB = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private SessionCipher newCipher(String keyId) {
        AppProperties.Secrets secrets = new AppProperties.Secrets(null, keyId, null, null, List.of());
        AppProperties properties = new AppProperties(null, null, null, null, secrets, null, null, null);
        return new SessionCipher(properties);
    }

    @Test
    void roundTripsUnderTheExactContextItWasEncryptedFor() {
        SessionCipher cipher = newCipher("k1");
        byte[] plaintext = "top secret session material".getBytes(StandardCharsets.UTF_8);

        byte[] envelope = cipher.encrypt(plaintext, this.tenantA, this.connectionA);

        assertThat(cipher.decrypt(envelope, this.tenantA, this.connectionA)).isEqualTo(plaintext);
    }

    @Test
    void refusesToDecryptUnderADifferentTenant() {
        SessionCipher cipher = newCipher("k1");
        byte[] envelope = cipher.encrypt("secret".getBytes(StandardCharsets.UTF_8),
                this.tenantA, this.connectionA);

        assertThatThrownBy(() -> cipher.decrypt(envelope, this.tenantB, this.connectionA))
                .isInstanceOf(SessionCipher.GeneralSecurityFailure.class);
    }

    @Test
    void refusesToDecryptUnderADifferentConnection() {
        SessionCipher cipher = newCipher("k1");
        byte[] envelope = cipher.encrypt("secret".getBytes(StandardCharsets.UTF_8),
                this.tenantA, this.connectionA);

        assertThatThrownBy(() -> cipher.decrypt(envelope, this.tenantA, this.connectionB))
                .isInstanceOf(SessionCipher.GeneralSecurityFailure.class);
    }

    @Test
    void refusesToDecryptUnderADifferentKeyId() {
        byte[] envelope = newCipher("k1").encrypt("secret".getBytes(StandardCharsets.UTF_8),
                this.tenantA, this.connectionA);

        assertThatThrownBy(() -> newCipher("k2").decrypt(envelope, this.tenantA, this.connectionA))
                .isInstanceOf(SessionCipher.GeneralSecurityFailure.class);
    }
}
