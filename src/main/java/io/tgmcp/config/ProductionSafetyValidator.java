package io.tgmcp.config;

import java.util.ArrayList;
import java.util.List;

import io.tgmcp.common.KeyMaterial;
import io.tgmcp.tenancy.Tenant;
import io.tgmcp.tenancy.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Component;

/**
 * Security-audit finding F10: a chat's demo/prod boundary and its cryptographic secrets are
 * enforced individually in a handful of places (see {@link TelegramConfiguration}, {@link
 * io.tgmcp.telegram.session.SessionCipher}, {@link io.tgmcp.mcp.CursorCodec}), but nothing
 * refused to start a deployment that combined real Telegram account access with settings that
 * only make sense for a local demo — synthetic seeding left on, development-derived
 * cryptographic keys, or an authorization server issued over plain HTTP. Each of those
 * individually logs a warning; none of them, alone or together, stops the process. This
 * component is the single fail-fast gate: whenever real account access is authorized, every
 * setting that would otherwise only be "wrong for production" becomes "refuses to start".
 *
 * <p>Constructed eagerly during context refresh (a plain {@code @Component}, not a lazy bean),
 * so an unsafe combination fails the deployment immediately rather than surfacing later as a
 * data leak or a demo fixture polluting a real account's chats.
 */
@Component
public class ProductionSafetyValidator {

    private static final Logger log = LoggerFactory.getLogger(ProductionSafetyValidator.class);

    public ProductionSafetyValidator(AppProperties properties,
                                     TenantRepository tenants,
                                     RegisteredClientRepository clients,
                                     @Value("${tgmcp.oauth.issuer:http://localhost:8080}") String issuer,
                                     @Value("${tgmcp.oauth.public-base-url:http://localhost:8080}") String publicBaseUrl,
                                     @Value("${server.servlet.session.cookie.secure:false}") boolean cookieSecure) {
        if (!properties.telegram().realAccountAccessAuthorized()) {
            // Every check below only matters once a real Telegram account can be contacted.
            return;
        }

        List<String> problems = new ArrayList<>();
        if (properties.demo().seedSyntheticData()) {
            problems.add("tgmcp.demo.seed-synthetic-data=true while a real Telegram account is "
                    + "authorized: synthetic demo fixtures must never coexist with real-account "
                    + "processing. Set TGMCP_SEED_DEMO=false.");
        }
        if (!KeyMaterial.isConfigured(properties.secrets().sessionEncryptionKey())) {
            problems.add("tgmcp.secrets.session-encryption-key is not set: real Telegram session "
                    + "material would otherwise be encrypted with a key derived deterministically "
                    + "from a label, not a secret. Set TGMCP_SESSION_ENCRYPTION_KEY.");
        }
        if (!KeyMaterial.isConfigured(properties.secrets().cursorSigningKey())) {
            problems.add("tgmcp.secrets.cursor-signing-key is not set: MCP pagination cursors "
                    + "would otherwise be signed with a key derived deterministically from a "
                    + "label, not a secret. Set TGMCP_CURSOR_SIGNING_KEY.");
        }
        // Security-audit finding F18: without a persistent key, every restart invalidates
        // every outstanding OAuth token, and every replica of a multi-instance deployment
        // mints tokens under its own key that no other replica can validate.
        if (!KeyMaterial.isConfigured(properties.secrets().oauthRsaPrivateKey())) {
            problems.add("tgmcp.secrets.oauth-rsa-private-key is not set: OAuth access tokens "
                    + "would otherwise be signed with a fresh ephemeral key generated on every "
                    + "startup. Set TGMCP_OAUTH_RSA_PRIVATE_KEY.");
        }
        if (!isHttps(issuer)) {
            problems.add("tgmcp.oauth.issuer (" + issuer + ") is not https://: OAuth tokens for a "
                    + "real account must not be issued or validated over plain HTTP.");
        }
        if (!isHttps(publicBaseUrl)) {
            problems.add("tgmcp.oauth.public-base-url (" + publicBaseUrl + ") is not https://.");
        }
        if (!cookieSecure) {
            problems.add("server.servlet.session.cookie.secure is not true: the session cookie "
                    + "would otherwise be sent over plain HTTP. Set TGMCP_COOKIE_SECURE=true "
                    + "(only safe once the app is actually served over HTTPS).");
        }
        // Security-audit finding SEC-07: F08 only ever logged this gap as a warning: a real
        // deployment could authorize real-account access against an adapter with no live
        // edit/deletion pipeline and start anyway. Turning that into a startup refusal, like
        // every other unsafe combination here, means processing real chats where edits or
        // deletions matter requires a deliberate, explicit acknowledgement rather than
        // depending on someone having read the log.
        if (!properties.telegram().adapter().supportsLiveRevisions()
                && !properties.telegram().acknowledgeMissingEditDeletionPipeline()) {
            problems.add("The " + properties.telegram().adapter() + " adapter has no live "
                    + "edit/deletion pipeline: a message edited or deleted on Telegram after ingestion "
                    + "will not be reflected here (see docs/production-prerequisites.md). Set "
                    + "tgmcp.telegram.acknowledge-missing-edit-deletion-pipeline=true only once you have "
                    + "confirmed this gap is acceptable for every chat you plan to activate.");
        }
        // Security-audit finding SEC-11: disabling tgmcp.demo.seed-synthetic-data stops new
        // demo records from being created, but does not delete ones a previous run already
        // inserted -- a database reused (or promoted) for real-account processing can still
        // carry a demo owner whose password may have reached old logs, a second demo tenant,
        // and demo MCP clients that were never reviewed for real use. Checked directly against
        // durable, deterministic markers (the tenants.demo_mode column, and the two fixed demo
        // client ids) rather than the currently configured demo email/slug, which could have
        // changed since seeding ran.
        List<Tenant> demoTenants = tenants.findAllDemo();
        if (!demoTenants.isEmpty()) {
            problems.add("Demo-seeded tenant(s) are still present while real-account access is "
                    + "authorized: " + demoTenants.stream().map(Tenant::slug).toList() + ". Disabling "
                    + "tgmcp.demo.seed-synthetic-data does not delete records a previous run already "
                    + "created; remove them, or use a database that never had seeding enabled.");
        }
        List<String> demoClientIds = List.of(DemoDataSeeder.CLAUDE_DEMO_CLIENT_ID,
                DemoDataSeeder.CHATGPT_DEMO_CLIENT_ID).stream()
                .filter(clientId -> clients.findByClientId(clientId) != null)
                .toList();
        if (!demoClientIds.isEmpty()) {
            problems.add("Demo-registered MCP client(s) are still present while real-account access is "
                    + "authorized: " + demoClientIds + ". Remove them before relying on registered-client "
                    + "identity for real provider traffic.");
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start: tgmcp.telegram.real-account-access-authorized=true, but "
                            + problems.size() + " setting(s) are unsafe for real-account use:\n - "
                            + String.join("\n - ", problems));
        }
        log.info("Production safety checks passed: real Telegram account access is authorized "
                + "with demo seeding disabled, configured secrets and an HTTPS-only OAuth/cookie setup.");
    }

    private static boolean isHttps(String url) {
        return url != null && url.startsWith("https://");
    }
}
