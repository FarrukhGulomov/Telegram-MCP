package io.tgmcp.config;

import java.time.Duration;
import java.util.List;

import io.tgmcp.telegram.gateway.TelegramAdapterKind;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("tgmcp")
public record AppProperties(@DefaultValue Telegram telegram,
                            @DefaultValue Mcp mcp,
                            @DefaultValue Consent consent,
                            @DefaultValue Sync sync,
                            @DefaultValue Secrets secrets,
                            @DefaultValue Demo demo,
                            @DefaultValue AbuseControls abuseControls,
                            @DefaultValue ForwardedHeaders forwardedHeaders) {

    public record Telegram(@DefaultValue("MOCK") TelegramAdapterKind adapter,
                           /** TDLib api_id / api_hash. Required only for the TDLIB adapter. */
                           String apiId,
                           String apiHash,
                           @DefaultValue("./tdlib-data") String databaseDirectory,
                           /**
                            * Real Telegram accounts must not be contacted unless an operator has
                            * explicitly authorized it, even when the TDLIB adapter is selected.
                            */
                           @DefaultValue("false") boolean realAccountAccessAuthorized,
                           /**
                            * Security-audit findings F08/SEC-07: {@link
                            * TelegramAdapterKind#supportsLiveRevisions} is {@code false} for
                            * TDLIB -- a pipeline that turns TDLib's pushed {@code
                            * updateMessageContent}/{@code updateDeleteMessages} events into
                            * revisions now exists ({@link
                            * io.tgmcp.telegram.tdlib.TdlibTelegramGateway}), but it has never
                            * been verified against a real Telegram account. {@link
                            * ProductionSafetyValidator} refuses real-account access against such
                            * an adapter unless this is explicitly set, so relying on unverified
                            * edit/delete sync is a deliberate, acknowledged choice rather than a
                            * discovered surprise.
                            */
                           @DefaultValue("false") boolean acknowledgeMissingEditDeletionPipeline) {

        /**
         * A copy of this config scoped to one tenant's own TDLib session directory, so that
         * per-tenant isolation ({@code RoutedTdlibTelegramGateway}) never lets two tenants'
         * native clients share on-disk session state.
         */
        public Telegram withDatabaseDirectory(String tenantDatabaseDirectory) {
            return new Telegram(this.adapter, this.apiId, this.apiHash, tenantDatabaseDirectory,
                    this.realAccountAccessAuthorized, this.acknowledgeMissingEditDeletionPipeline);
        }
    }

    /**
     * Hard caps for MCP responses. Every one of these is enforced server-side; a client
     * cannot raise them by asking.
     */
    public record Mcp(@DefaultValue("50") int defaultPageSize,
                      @DefaultValue("200") int maxPageSize,
                      @DefaultValue("20") int maxContextMessages,
                      @DefaultValue("512") int maxQueryLength,
                      @DefaultValue("20") int maxChatIdsPerQuery,
                      @DefaultValue("262144") int maxResponseBytes,
                      @DefaultValue("4000") int maxMessageTextChars,
                      @DefaultValue("366") int maxRangeDays,
                      @DefaultValue("PT15M") Duration cursorTtl,
                      @DefaultValue("120") int rateLimitRequestsPerMinute,
                      /**
                       * The canonical resource identifier this server accepts tokens for
                       * (RFC 8707 / RFC 9728). Tokens whose audience does not include it are rejected.
                       */
                      @DefaultValue("http://localhost:8080/mcp") String resourceIdentifier,
                      @DefaultValue("mcp:read") String requiredScope) {
    }

    public record Consent(@DefaultValue("2026-09-v1") @NotBlank String currentVersion,
                          @DefaultValue("true") boolean requireAllParticipants,
                          @DefaultValue("P365D") Duration defaultValidity) {
    }

    public record Sync(@DefaultValue("PT30S") Duration interval,
                       @DefaultValue("200") int pageSize,
                       @DefaultValue("5") int maxPagesPerRun,
                       @DefaultValue("2") int maxConcurrentChats,
                       @DefaultValue("6") int maxConsecutiveFailures,
                       @DefaultValue("PT5S") Duration initialBackoff,
                       @DefaultValue("PT10M") Duration maxBackoff,
                       @DefaultValue("PT1H") Duration retentionInterval,
                       /**
                        * Security-audit finding SEC-06: consent coverage is only ever computed
                        * from the participant list last confirmed by a successful sync -- once
                        * upstream authorization is lost (the account is logged out, banned, or
                        * loses permission), that list can never be reconfirmed again, yet
                        * nothing stopped MCP reads from continuing to trust it indefinitely.
                        * Once a chat's last successful sync is older than this, it stops being
                        * MCP-readable until synchronisation succeeds again.
                        */
                       @DefaultValue("PT24H") Duration membershipFreshnessBudget) {
    }

    /**
     * Key material. These are read from the environment and deliberately never stored in
     * the database next to the data they protect.
     */
    public record Secrets(/** Base64 32-byte AES key used to encrypt Telegram session material. */
                          String sessionEncryptionKey,
                          @DefaultValue("local-dev") String sessionEncryptionKeyId,
                          /** Base64 32-byte key used to sign opaque MCP pagination cursors. */
                          String cursorSigningKey,
                          /**
                           * Base64-encoded PKCS8 RSA private key for signing OAuth access tokens
                           * (security-audit finding F18). Without it, a fresh RSA key pair is
                           * generated on every startup, which invalidates every outstanding
                           * token on restart and gives each replica of a multi-instance
                           * deployment its own, mutually-incompatible key.
                           */
                          String oauthRsaPrivateKey,
                          /**
                           * Security-audit finding SEC-17: rotating {@code oauthRsaPrivateKey}
                           * used to invalidate every already-issued, still-valid access token
                           * immediately -- the JWK set exposed exactly one key, so a token
                           * signed under the previous key could no longer be verified the
                           * instant the new one took over. Each entry here is one previous
                           * signing key's public half only (never its private key), in
                           * {@code keyId:base64X509PublicKey} form; {@link
                           * AuthorizationServerConfig#jwkSource} adds it to the JWK set as
                           * verification-only, so a token signed before rotation keeps
                           * verifying for as long as its entry stays configured, while every
                           * new token is still signed only with the current key (a JWK with no
                           * private key can never be selected for signing). Remove an entry
                           * once its overlap window has passed.
                           */
                          @DefaultValue List<String> retiringOauthVerificationKeys) {
    }

    public record Demo(@DefaultValue("true") boolean seedSyntheticData,
                       @DefaultValue("demo") String tenantSlug,
                       @DefaultValue("Synthetic Demo Team") String tenantName,
                       @DefaultValue List<String> extraUsers,
                       /**
                        * Security-audit finding SEC-11's remediation: a database promoted to
                        * real-account use can still carry demo tenants/OAuth clients a previous
                        * run seeded, which disabling {@link #seedSyntheticData} alone does not
                        * delete. Setting this to {@code true} makes {@link
                        * DemoDataCleanupRunner} remove them once, on the next startup. Off by
                        * default; turn it back off once the log confirms the cleanup ran.
                        */
                       @DefaultValue("false") boolean cleanupLegacyDemoData) {
    }

    /**
     * Security-audit finding SEC-10: {@code Mcp#rateLimitRequestsPerMinute} only ever budgets
     * MCP tool calls, made by an already-authenticated caller. Every endpoint reachable before
     * or without that authentication -- sign-in, OAuth token issuance, dynamic client
     * registration -- had no independent budget of its own, and Telegram onboarding actions
     * (which can trigger real, costly native TDLib work) shared no budget distinct from
     * ordinary page views. These are deliberately separate, smaller numbers, keyed by client
     * IP for the pre-authentication endpoints and by tenant/user for onboarding.
     */
    public record AbuseControls(@DefaultValue("10") int loginAttemptsPerMinutePerIp,
                                @DefaultValue("20") int oauthTokenRequestsPerMinutePerIp,
                                @DefaultValue("5") int oauthRegistrationsPerHourPerIp,
                                @DefaultValue("30") int connectActionsPerMinutePerUser,
                                /**
                                 * Self-service account creation (a workspace/email submission
                                 * followed by linking a Telegram identity) is reachable without
                                 * any prior authentication and creates a durable tenant + user
                                 * row per success -- its own budget, IP keyed, distinct from the
                                 * plain login-attempt budget above.
                                 */
                                @DefaultValue("5") int selfRegistrationsPerHourPerIp,
                                /**
                                 * Self-service account actions reachable by anyone already
                                 * holding a valid session (currently: revoking a connected app's
                                 * OAuth authorization). Per-user, not per-IP, since the caller is
                                 * already authenticated by the time this applies.
                                 */
                                @DefaultValue("10") int accountActionsPerMinutePerUser,
                                /**
                                 * Security-audit finding SEC-R02: the IP-keyed budget above still
                                 * lets one attacker who knows a victim's phone number (rotating
                                 * source IPs, or just waiting out the per-IP window) repeatedly
                                 * call {@code POST /login/start} for that number, each attempt
                                 * tearing down the victim's existing Telegram connection via
                                 * {@code PreAuthLoginService#begin}'s {@code
                                 * prepareForFreshLogin} before any proof of ownership. Keyed by
                                 * the normalized phone number itself rather than the caller's IP,
                                 * this bounds how often any single number's connection can be
                                 * disrupted at all, regardless of how many different addresses an
                                 * attacker spreads attempts across. Deliberately a small
                                 * long-window budget: a real device retrying its own login within
                                 * a minute or two is common, but sustained repeated teardown of
                                 * the same number many times an hour is not.
                                 */
                                @DefaultValue("5") int loginAttemptsPerHourPerPhone,
                                /**
                                 * Security-audit finding SEC-R08: {@code
                                 * PreAuthLoginService#createAccount} creates a permanent
                                 * tenant/user row for a new phone number the moment a login
                                 * attempt starts, before Telegram's own code (and password, if
                                 * set) ever proves the visitor holds it -- TDLib needs a stable
                                 * per-tenant identity to run its login state machine against
                                 * from the very first request, so that row cannot simply be
                                 * deferred. A visitor who mistypes a number, closes the tab, or
                                 * otherwise never finishes leaves it behind permanently unless
                                 * they explicitly cancel. {@link
                                 * io.tgmcp.tenancy.AbandonedSignupReaper} deletes any such
                                 * tenant whose connection has still never reached {@code READY}
                                 * once it is older than this.
                                 */
                                @DefaultValue("PT1H") Duration abandonedSignupMaxAge) {
    }

    /**
     * Security-audit finding SEC-13: {@code server.forward-headers-strategy=framework} makes
     * Spring trust {@code X-Forwarded-Host}/{@code -Proto}/{@code -Port} unconditionally --
     * the right default when a platform's own edge (Railway, Heroku, ...) always sits between
     * the internet and this process, but unproven and unenforced if this port is ever reachable
     * directly or an intermediate hop passes those headers through unfiltered. Once {@code
     * trustedHosts} is non-empty, {@link io.tgmcp.security.TrustedForwardedHostFilter} rejects
     * any request whose effective host (after Spring has already applied the forwarded headers)
     * is not in this list, so a forged header can never make this deployment believe it is
     * reachable at a hostname it was not actually configured for.
     */
    public record ForwardedHeaders(@DefaultValue List<String> trustedHosts) {
    }
}
