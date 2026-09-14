package io.tgmcp.common;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Turns a configured base64 secret into an AES/HMAC key.
 *
 * <p>When no key is configured we derive a deterministic development key from a label and
 * loudly mark it as such, so that a missing credential never blocks local development but
 * also never silently looks like a production key.
 */
public final class KeyMaterial {

    private static final int AES_KEY_BYTES = 32;
    private static final int MIN_MAC_KEY_BYTES = 32;

    private KeyMaterial() {
    }

    public static SecretKey load(String base64Key, String algorithm, String devLabel) {
        byte[] raw;
        if (base64Key == null || base64Key.isBlank()) {
            raw = developmentKeyBytes(devLabel);
        }
        else {
            try {
                raw = Base64.getDecoder().decode(base64Key.trim());
            }
            catch (IllegalArgumentException ex) {
                throw new IllegalStateException("Key '" + devLabel + "' is not valid base64", ex);
            }
        }
        // Validated here, at startup, rather than at first use: AES-256 accepts exactly 32
        // bytes, and a 33-byte key would otherwise fail only when someone first connects.
        if ("AES".equals(algorithm)) {
            if (raw.length != AES_KEY_BYTES) {
                throw new IllegalStateException("Key '" + devLabel + "' must decode to exactly "
                        + AES_KEY_BYTES + " bytes for AES-256, but decoded to " + raw.length);
            }
        }
        else if (raw.length < MIN_MAC_KEY_BYTES) {
            throw new IllegalStateException("Key '" + devLabel + "' must decode to at least "
                    + MIN_MAC_KEY_BYTES + " bytes, but decoded to " + raw.length);
        }
        return new SecretKeySpec(raw, algorithm);
    }

    public static boolean isConfigured(String base64Key) {
        return base64Key != null && !base64Key.isBlank();
    }

    /**
     * Derives a distinct 32-byte key from an already-configured secret, HMAC-keyed by it with
     * {@code derivationLabel} as the message. Used to give a second consumer (security-audit
     * finding F19: TDLib's own at-rest database encryption) its own key without asking for a
     * second secret to configure and without ever reusing the same key bytes across two
     * different cryptographic uses.
     */
    public static byte[] derive(String base64Key, String devLabel, String derivationLabel) {
        SecretKey base = load(base64Key, "HmacSHA256", devLabel);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(base);
            return mac.doFinal(derivationLabel.getBytes(StandardCharsets.UTF_8));
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to derive key material for '" + derivationLabel + "'", ex);
        }
    }

    private static byte[] developmentKeyBytes(String devLabel) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(("tgmcp-insecure-development-key/" + devLabel).getBytes(StandardCharsets.UTF_8));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
