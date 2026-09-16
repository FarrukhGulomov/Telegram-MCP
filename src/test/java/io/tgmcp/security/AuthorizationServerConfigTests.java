package io.tgmcp.security;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.tgmcp.config.AppProperties;
import io.tgmcp.telegram.gateway.TelegramAdapterKind;
import org.junit.jupiter.api.Test;

import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

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
        return propertiesWithOauthKey(oauthRsaPrivateKey, List.of());
    }

    private static AppProperties propertiesWithOauthKey(String oauthRsaPrivateKey, List<String> retiringKeys) {
        AppProperties.Telegram telegram = new AppProperties.Telegram(TelegramAdapterKind.MOCK,
                null, null, "./tdlib", false, false);
        AppProperties.Secrets secrets = new AppProperties.Secrets(null, "test", null, oauthRsaPrivateKey,
                retiringKeys);
        AppProperties.Demo demo = new AppProperties.Demo(false, "demo", "Demo", "a@b.invalid", "pw", List.of());
        return new AppProperties(telegram, null, null, null, secrets, demo, null, null);
    }

    private static KeyPair freshRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String freshBase64Pkcs8Key() throws Exception {
        return Base64.getEncoder().encodeToString(freshRsaKeyPair().getPrivate().getEncoded());
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

    /**
     * Security-audit finding SEC-17: a lone signing key meant that rotating {@code
     * tgmcp.secrets.oauth-rsa-private-key} invalidated every already-issued, still-valid access
     * token immediately -- nothing verified a token signed under the previous key once the new
     * one took over. These tests exercise the actual overlap: a token signed with a retired
     * key's private half must still decode successfully once that key's public half is
     * configured as a retiring verification entry, while a token from a key nobody configured
     * at all must still be rejected.
     */
    @Test
    void aTokenSignedByARetiredKeyStillVerifiesOnceItsPublicHalfIsConfiguredAsRetiring() throws Exception {
        KeyPair currentKeyPair = freshRsaKeyPair();
        KeyPair retiredKeyPair = freshRsaKeyPair();
        String currentPrivateKeyBase64 = Base64.getEncoder().encodeToString(currentKeyPair.getPrivate().getEncoded());
        String retiredPublicKeyEntry = "retired-2026-01:"
                + Base64.getEncoder().encodeToString(retiredKeyPair.getPublic().getEncoded());

        AppProperties properties = propertiesWithOauthKey(currentPrivateKeyBase64, List.of(retiredPublicKeyEntry));
        var jwkSource = CONFIG.jwkSource("current-2026-02", properties);
        JwtDecoder decoder = OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);

        String tokenFromRetiredKey = signToken(retiredKeyPair, "retired-2026-01");
        Jwt decoded = decoder.decode(tokenFromRetiredKey);
        assertThat(decoded.getSubject()).isEqualTo("test-subject");
    }

    @Test
    void aTokenSignedByAKeyNobodyConfiguredIsStillRejected() throws Exception {
        KeyPair currentKeyPair = freshRsaKeyPair();
        KeyPair neverConfiguredKeyPair = freshRsaKeyPair();
        String currentPrivateKeyBase64 = Base64.getEncoder().encodeToString(currentKeyPair.getPrivate().getEncoded());

        AppProperties properties = propertiesWithOauthKey(currentPrivateKeyBase64, List.of());
        var jwkSource = CONFIG.jwkSource("current-2026-02", properties);
        JwtDecoder decoder = OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);

        String tokenFromUnknownKey = signToken(neverConfiguredKeyPair, "some-other-key");

        assertThatThrownBy(() -> decoder.decode(tokenFromUnknownKey)).isInstanceOf(JwtException.class);
    }

    @Test
    void retiringVerificationKeysAreNeverEligibleToSignANewToken() throws Exception {
        KeyPair currentKeyPair = freshRsaKeyPair();
        KeyPair retiredKeyPair = freshRsaKeyPair();
        String currentPrivateKeyBase64 = Base64.getEncoder().encodeToString(currentKeyPair.getPrivate().getEncoded());
        String retiredPublicKeyEntry = "retired-2026-01:"
                + Base64.getEncoder().encodeToString(retiredKeyPair.getPublic().getEncoded());

        AppProperties properties = propertiesWithOauthKey(currentPrivateKeyBase64, List.of(retiredPublicKeyEntry));
        var jwkSource = CONFIG.jwkSource("current-2026-02", properties);

        // The distinguishing property Spring Authorization Server's own signer relies on: only
        // a JWK with a private key present can ever be selected to sign. A verification-only
        // entry (built with no private key at all) must never match this selector.
        var signingSelector = new com.nimbusds.jose.jwk.JWKSelector(new com.nimbusds.jose.jwk.JWKMatcher.Builder()
                .privateOnly(true)
                .build());
        var signingCandidates = jwkSource.get(signingSelector, null);

        assertThat(signingCandidates).hasSize(1);
        assertThat(((RSAKey) signingCandidates.get(0)).getKeyID()).isEqualTo("current-2026-02");
    }

    @Test
    void rejectsARetiringEntryThatReusesTheCurrentSigningKeyId() throws Exception {
        KeyPair currentKeyPair = freshRsaKeyPair();
        KeyPair retiredKeyPair = freshRsaKeyPair();
        String currentPrivateKeyBase64 = Base64.getEncoder().encodeToString(currentKeyPair.getPrivate().getEncoded());
        String collidingEntry = "current-2026-02:"
                + Base64.getEncoder().encodeToString(retiredKeyPair.getPublic().getEncoded());

        AppProperties properties = propertiesWithOauthKey(currentPrivateKeyBase64, List.of(collidingEntry));

        assertThatThrownBy(() -> CONFIG.jwkSource("current-2026-02", properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current-2026-02");
    }

    @Test
    void rejectsAMalformedRetiringKeyEntry() {
        assertThatThrownBy(() -> AuthorizationServerConfig.loadRetiringVerificationKey("not-a-valid-entry"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retiring-oauth-verification-keys");
    }

    private static String signToken(KeyPair keyPair, String keyId) throws Exception {
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(keyId).build();
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("test-subject")
                .issuer("https://issuer.invalid")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .build();
        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(new RSASSASigner(keyPair.getPrivate()));
        return jwt.serialize();
    }
}
