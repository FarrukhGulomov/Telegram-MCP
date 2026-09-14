package io.tgmcp.telegram.session;

import java.nio.ByteBuffer;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import io.tgmcp.common.KeyMaterial;
import io.tgmcp.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * AES-256-GCM envelope for Telegram session material.
 *
 * <p>The key is supplied through configuration (environment / secret manager) and is never
 * written to the database — only {@code sessionEncryptionKeyId} is persisted alongside the
 * ciphertext so that keys can be rotated.
 */
@Component
public class SessionCipher {

    private static final Logger log = LoggerFactory.getLogger(SessionCipher.class);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final SecretKey key;
    private final String keyId;

    public SessionCipher(AppProperties properties) {
        AppProperties.Secrets secrets = properties.secrets();
        this.key = KeyMaterial.load(secrets.sessionEncryptionKey(), "AES", "session-encryption");
        this.keyId = secrets.sessionEncryptionKeyId();
        if (!KeyMaterial.isConfigured(secrets.sessionEncryptionKey())) {
            log.warn("TGMCP_SESSION_ENCRYPTION_KEY is not set; using a derived development key. "
                    + "Set a real 32-byte base64 key before storing any real Telegram session.");
        }
    }

    public String keyId() {
        return this.keyId;
    }

    public byte[] encrypt(byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            this.random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, this.key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
        }
        catch (GeneralSecurityFailure ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new GeneralSecurityFailure("Unable to encrypt session material", ex);
        }
    }

    public byte[] decrypt(byte[] envelope) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(envelope);
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] sealed = new byte[buffer.remaining()];
            buffer.get(sealed);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, this.key, new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(sealed);
        }
        catch (Exception ex) {
            throw new GeneralSecurityFailure("Unable to decrypt session material", ex);
        }
    }

    /** Never carries the offending value in its message. */
    public static class GeneralSecurityFailure extends RuntimeException {
        public GeneralSecurityFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
