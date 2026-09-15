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
                            @DefaultValue Demo demo) {

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
                            * TDLIB -- edits and deletions made on Telegram after ingestion are
                            * silently not reflected here, with no pipeline built to change that.
                            * {@link ProductionSafetyValidator} refuses real-account access
                            * against such an adapter unless this is explicitly set, so that gap
                            * is a deliberate, acknowledged choice rather than a discovered
                            * surprise.
                            */
                           @DefaultValue("false") boolean acknowledgeMissingEditDeletionPipeline) {
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
                          String oauthRsaPrivateKey) {
    }

    public record Demo(@DefaultValue("true") boolean seedSyntheticData,
                       @DefaultValue("demo") String tenantSlug,
                       @DefaultValue("Synthetic Demo Team") String tenantName,
                       @DefaultValue("owner@demo.invalid") String ownerEmail,
                       @DefaultValue("demo-password") String ownerPassword,
                       @DefaultValue List<String> extraUsers) {
    }
}
