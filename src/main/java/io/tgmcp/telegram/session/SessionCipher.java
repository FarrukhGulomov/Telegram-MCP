package io.tgmcp.telegram.session;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;

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
 *
 * <p>Security-audit finding SEC-R15: GCM's authentication tag proves the ciphertext bytes
 * were not altered, but says nothing about which row they belong in. Without additional
 * authenticated data (AAD), a ciphertext copied verbatim into a different tenant's or
 * connection's row -- a restored backup applied against the wrong environment, a bug in a
 * future migration, an operator's copy/paste -- would still decrypt "successfully" under the
 * same shared key, silently handing that tenant's connection Telegram session material that
 * was never issued to it. Binding the tenant id, connection id, the key id the row itself
 * claims to be encrypted under, and an envelope schema version into the AAD means a ciphertext
 * only ever decrypts back out in the exact context it was encrypted for; anywhere else, GCM's
 * tag check fails closed.
 */
@Component
public class SessionCipher {

    private static final Logger log = LoggerFactory.getLogger(SessionCipher.class);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int SCHEMA_VERSION = 1;

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

    public byte[] encrypt(byte[] plaintext, UUID tenantId, UUID connectionId) {
        try {
            byte[] iv = new byte[IV_BYTES];
            this.random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, this.key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(additionalData(tenantId, connectionId));
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

    public byte[] decrypt(byte[] envelope, UUID tenantId, UUID connectionId) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(envelope);
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] sealed = new byte[buffer.remaining()];
            buffer.get(sealed);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, this.key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(additionalData(tenantId, connectionId));
            return cipher.doFinal(sealed);
        }
        catch (Exception ex) {
            throw new GeneralSecurityFailure("Unable to decrypt session material", ex);
        }
    }

    /**
     * Binds the ciphertext to exactly the row it belongs in: the tenant and connection it was
     * encrypted for, the key id this instance is currently configured with, and a schema
     * version that must be bumped if the envelope's own layout (IV length, tag length) ever
     * changes -- so an old envelope decrypted under new code fails the same authentication
     * check rather than being silently misinterpreted.
     */
    private byte[] additionalData(UUID tenantId, UUID connectionId) {
        String aad = "tgmcp-session:v" + SCHEMA_VERSION + ":" + tenantId + ":" + connectionId + ":" + this.keyId;
        return aad.getBytes(StandardCharsets.UTF_8);
    }

    /** Never carries the offending value in its message. */
    public static class GeneralSecurityFailure extends RuntimeException {
        public GeneralSecurityFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
