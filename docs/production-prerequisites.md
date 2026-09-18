# Production prerequisites and unresolved questions

This MVP is a working demonstration on synthetic data. Below is what stands between it and a
real deployment, stated plainly rather than optimistically.

## 1. Telegram terms — unresolved, and not resolved by this document

**I was unable to retrieve Telegram's current API Terms of Service or Content Licensing terms
while building this.** Outbound network access to `core.telegram.org` is blocked in the
environment this was developed in. Rather than summarise those documents from memory — which
would risk stating a term inaccurately in exactly the place accuracy matters — the specific
questions that must be answered before any real account is connected are listed here.

Read, with counsel, at minimum:

- Telegram API Terms of Service — <https://core.telegram.org/api/terms>
- Telegram Content Licensing terms — <https://telegram.org/tos> and the developer-facing
  licensing pages linked from <https://core.telegram.org>
- TDLib's own documentation on acceptable use — <https://core.telegram.org/tdlib>

Questions to resolve:

1. **Is a server-side user-account client permitted for this purpose?** This service runs a
   TDLib *user* session, not a bot. Confirm that a non-interactive, server-hosted user client
   that reads group history for an organisation is within the API terms.
2. **What does Telegram permit regarding storing message content off-platform, and for how
   long?** Our retention model must fit inside whatever that allows, not merely inside what
   participants consented to.
3. **What does Telegram permit regarding disclosing message content to a third party** — an
   AI provider — that is not a party to the conversation?
4. **Does the intended use fall under any content-licensing restriction** on redistribution
   or derived works?
5. **Are there rate, volume or purpose limits** attached to the api_id you obtain?
6. **What are the obligations when a user deletes a message or their account?** Our tombstone
   behaviour drops content immediately; confirm that satisfies the requirement.

Nothing in this codebase should be read as an assertion that any of the above is permitted.

## 2. Consent does not equal compliance

Recorded participant consent is a control this service enforces. It is not a legal
conclusion. Before launch, establish with counsel:

- the lawful basis for processing in each jurisdiction where a participant sits (consent is
  one option and often not the strongest);
- whether participants are employees, where consent may not be freely given;
- controller/processor roles between your organisation, this service and the AI provider;
- data-subject rights machinery — access, erasure, objection — including for people
  *mentioned in* messages who never joined the chat;
- cross-border transfer mechanics for the provider you send content to;
- whether any special-category data is realistically present in the chats you plan to
  activate, and what that changes;
- retention and breach-notification obligations.

## 3. Engineering work not done in this MVP

**Verified against a live Telegram account: no.** The TDLib adapter's JSON translation is
covered by unit tests against a scripted client, using method and object shapes taken from
the current `td_api.tl` and `td_json_client.h` in the `tdlib/td` repository. The native
transport has not been exercised: no `libtdjson` binary was available in the build
environment, and connecting a real account was outside the authorization for this work. Treat
the adapter as unproven until you have run it end to end. Specifically untested: the
`td_receive` loop under load, TDLib's authorization state machine in real time, and
`updateMessageContent` / `updateDeleteMessages` handling.

**Edit/deletion pipeline exists but is unverified against a real account.** `fetchRevisions()`
used to unconditionally return an empty list for the TDLIB adapter. It no longer does:
`TdlibTelegramGateway` now listens for TDLib's pushed `updateMessageContent` /
`updateDeleteMessages` events, translates each into a `RemoteMessage` (re-fetching the full
message via `getMessage` for an edit; a message id alone is enough for a deletion), and
buffers it per chat, keyed the same way the rest of this class enforces single-owner locking
(security-audit finding F08). `fetchRevisions()` drains that buffer on each sync tick,
bridging TDLib's push model onto `ChatSyncService`'s pull-based contract without changing that
contract, `TelegramGateway`, or `RemoteMessage` — the same contract the MOCK adapter's own real
`fetchRevisions()` implementation already proves is generic enough.

Two things this does **not** yet close, both still tracked by
`tgmcp.telegram.acknowledge-missing-edit-deletion-pipeline` and
`TelegramAdapterKind.supportsLiveRevisions()` returning `false` for TDLIB:

1. **Unverified against a real Telegram account.** The JSON shapes this pipeline expects for
   `updateMessageContent`/`updateDeleteMessages` are taken from TDLib's own documented
   `td_api.tl`, covered by unit tests against a scripted client, but — like the rest of the
   TDLIB adapter — never exercised against `td_receive()` output from a live account. Treat
   edit/delete sync as unproven until that E2E run has happened (see the acceptance criteria in
   `docs/competitive-gap-analysis.md`).
2. **In-memory buffering only.** A revision buffered between the moment TDLib pushes it and the
   next sync draining it does not survive a process restart in that window — an accepted,
   narrower gap than "no pipeline at all," but still a gap: a restart at the wrong moment can
   silently lose one pending edit or deletion. A durable (database-backed) buffer would close
   this if it ever proves to matter in practice; it is not built pre-emptively here.

The application still logs a warning every time the TDLIB adapter starts, now describing this
narrower, unverified-not-missing state (see `TelegramConfiguration`). **Do not deploy the TDLIB
adapter for any chat where stale edits or undetected deletions are a real risk until a real
account has exercised this pipeline end to end.**

**Verified against live Claude or ChatGPT: no.** The MCP server is driven end to end by the
official MCP Java SDK client in the test suite (initialize, tools/list, tools/call, and the
authentication rejections). It has not been connected to Claude or ChatGPT. The
implementation follows MCP specification revision 2025-11-25 for authorization; confirm
against the revision your clients negotiate.

Also outstanding:

- **Dynamic client registration is open.** The RFC 7591 endpoint accepts any registration.
  Production needs registration policy: allowed redirect URIs, allowed scopes, approval, and
  rate limiting.
- **The provider a token authorizes is carried in a scope** (`provider:anthropic_claude`).
  That is derived from validated authentication, but it is only as trustworthy as your
  client-registration policy. Tighten (3) before relying on (this).
- **No HTTPS termination, no CSRF-safe reverse-proxy configuration, no secret manager
  integration** is included. The `tgmcp.secrets.*` keys (`session-encryption-key`,
  `cursor-signing-key`, `oauth-rsa-private-key`) are read from plain environment variables;
  production should source them from a real secret manager or KMS instead, and rotate them
  the way that manager expects.
- **Single-node assumptions**: the rate limiter is in-memory and the sync scheduler assumes
  one instance. Running more than one replica needs a shared limiter and either leader
  election or per-chat locking. A shared, persisted `oauth-rsa-private-key` (security-audit
  finding F18) makes issued tokens portable across replicas; the rate limiter and sync
  scheduler are not yet.
- **Backups.** See [retention-policy.md](retention-policy.md). This now also covers the
  TDLIB adapter's local database directory (security-audit finding F19): TDLib encrypts it
  at rest with a key this application derives and passes at every startup, so a backup of
  that directory is only restorable together with the `tgmcp.secrets.session-encryption-key`
  that was active when it was written — losing that key permanently loses the directory's
  contents, this application's own consent/message data in Postgres is unaffected.
- **Volume permissions and host-level access to `tgmcp.telegram.database-directory`** are an
  operational obligation this codebase cannot enforce: encrypting the directory's contents
  protects a stolen copy of the files, not a process that can read them through TDLib itself
  or through the filesystem while the key is in memory. Restrict who and what can reach that
  volume the same as you would any other credential store.
- **Participant notification.** Nothing here tells participants their chat is being
  processed. That is an operational obligation.
- **Database role separation (security-audit finding SEC-12).** `docker-compose.yml` now
  refuses to start without an explicit, non-default `TGMCP_DB_PASSWORD` and binds the
  published Postgres port to loopback by default, but the application still connects as a
  single Postgres role that owns the schema. Production should separate that into at least
  three roles: one used only for bootstrap/migration (schema DDL), one used by the running
  application for DML (the one actually configured as `TGMCP_DB_USER`), and one used only by
  whatever writes audit records. Before going live, inspect `pg_roles` and the runtime role's
  effective grants to confirm it is not superuser, not the database owner, and does not have
  `BYPASSRLS`. Row-level security and composite tenant foreign keys are worth adding as
  defense in depth, but neither is a substitute for the application's own tenant
  authorization checks. This depends on the specific hosting provider's administrative
  access model, so it is left as an operational task rather than a migration this codebase
  applies for you.
- **Audit transaction consistency and append-only storage (security-audit finding SEC-15).**
  `AuditService.record` runs in its own `REQUIRES_NEW` transaction specifically so an audit
  row survives a caller's later rollback (finding F20) -- the flip side is that a state-changing
  service which records `ALLOWED` (or a successful outcome) before its own surrounding
  transaction commits can leave that row saying a change succeeded when the enclosing
  transaction still fails afterward. A correct fix is a transaction-consistent outbox (decision,
  attempt, and committed-outcome recorded as distinct events, or an after-commit hook) rather
  than a same-transaction write, and is a larger design change than this pass attempts. Two
  related gaps: the database role this application connects as has ordinary `INSERT`/`UPDATE`
  privileges on `audit_events`, not a restricted append-only grant enforced at the database
  level (see the role-separation gap above); and nested `REQUIRES_NEW` audit transactions each
  borrow a connection from the same pool (`spring.datasource.hikari.maximum-pool-size: 10`) as
  the surrounding request, so a burst of concurrent state-changing calls could exhaust it while
  waiting on their own audit write -- this needs load testing against a real deployment's
  expected concurrency, not a change this codebase can safely make blind.
- **Release pipeline coverage beyond the test gate (security-audit finding SEC-18).** CI now
  enforces the test suite and builds a report-only container vulnerability scan (Trivy,
  `CRITICAL,HIGH`, non-blocking); it does not yet: fail the build on unresolved High/Critical
  findings (this needs the current findings triaged first, so day-one noise doesn't make the
  gate impractical); run targeted concurrency/race tests as their own suite; generate an SBOM;
  verify TDLib native-library provenance; publish images by immutable digest with signing
  (e.g. cosign) rather than a mutable tag; or enforce branch protection requiring this workflow
  to pass before merge, which is a GitHub repository setting outside this codebase's control.
  These are real gaps in the audit's fuller remediation, left as explicit operational follow-up
  rather than pipeline steps added without anything to verify them against in this environment.
- **Key rotation and recovery beyond OAuth token overlap (security-audit finding SEC-17).**
  `tgmcp.secrets.retiring-oauth-verification-keys` now gives OAuth access tokens an actual
  rotation overlap window (see section 4). Three related gaps remain unaddressed: (1)
  `SessionCipher` still decrypts with exactly one configured key -- `sessionEncryptionKeyId`
  is persisted alongside every encrypted session envelope specifically so it *could* select
  among several keys, but nothing reads it back for that purpose, so
  rotating `tgmcp.secrets.session-encryption-key` would make any previously-encrypted envelope
  permanently unreadable; (2) the TDLib native database encryption key is derived from that
  same root secret (finding F19), so rotating it also changes the key needed to open the
  existing on-disk TDLib database -- there is no rekey procedure, and testing one requires the
  actual native library and version this codebase cannot exercise in an environment without it;
  operators must treat rotating `tgmcp.secrets.session-encryption-key` as equivalent to a full
  TDLib re-authentication (a fresh login into a new, empty database directory), not an
  in-place key change; (3) the PostgreSQL `session_ciphertext` envelope
  (`TelegramConnectionService`) is a placeholder handle rather than a real backup of native
  session state -- it is written but never read back and decrypted anywhere in this codebase,
  so it does not currently help recovery at all. A complete fix needs a versioned keyring (not
  just a recorded id) for `SessionCipher`, a decoupled TDLib key with its own rotation and a
  verified native rekey procedure, and either wiring the Postgres envelope into real recovery
  or removing it if it is not meant to be one.
- **Self-service sign-up (email/password registration, and "Sign in with Telegram") has no
  account-recovery or account-linking story.** `/register` and `TelegramLoginController` let a
  new team create its own tenant without an operator, but: there is no "forgot password" flow,
  so a lost password on a self-registered account has no recovery path short of direct database
  access; a Telegram-provisioned account has no password at all (a random, never-surfaced value
  satisfies `password_hash NOT NULL`) and no email address that can receive anything (a
  synthetic `telegram-<id>@users.tgmcp.invalid` address), so losing access to that Telegram
  account permanently loses the tenant; and there is no way to link an existing password
  account to a Telegram identity or vice versa -- each is a wholly separate front door that
  provisions its own tenant, never merges with one that already exists. `TelegramLoginController`
  itself also depends on the operator having registered this deployment's exact public domain
  with the login bot via BotFather's `/setdomain`; nothing in this codebase can verify that
  registration is in place beyond `tgmcp.telegram-login.bot-token`/`bot-username` being
  non-blank; and self-registration's abuse control
  (`tgmcp.abuse-controls.self-registrations-per-hour-per-ip`) is the same best-effort,
  IP-keyed, proxy-boundary-dependent limiter security-audit finding SEC-10 already flagged for
  login and OAuth token issuance -- not a substitute for a real CAPTCHA or email-verification
  step if this is exposed to the open internet.

## 4. Safety interlocks currently in place

- The default adapter is `MOCK`; no Telegram account is contacted.
- The `TDLIB` adapter refuses to start unless
  `tgmcp.telegram.real-account-access-authorized=true` is set explicitly, *in addition* to
  supplying `api_id` and `api_hash`.
- Missing keys produce a derived development key and a loud warning rather than a silent
  weak default.
- The TDLIB adapter's local database directory is encrypted at rest with a key TDLib
  itself manages, derived from `tgmcp.secrets.session-encryption-key` at every startup
  (security-audit finding F19) rather than left unencrypted the way `setTdlibParameters`
  does by default.
- The OAuth access-token signing key is persisted and shared across replicas when
  `tgmcp.secrets.oauth-rsa-private-key` is configured, and a request naming a `resource`
  other than this server's own is rejected outright rather than silently ignored
  (security-audit finding F18); `ProductionSafetyValidator` refuses to start with real
  account access authorized until that key is set.
- Sensitive chats stay `INACTIVE` until someone activates them deliberately; the synthetic
  dataset includes one such chat to make the point.
- `server.forward-headers-strategy=framework` trusts `X-Forwarded-Host`/`-Proto`/`-Port`
  unconditionally (security-audit finding SEC-13); `ProductionSafetyValidator` refuses to
  start with real account access authorized unless `tgmcp.forwarded-headers.trusted-hosts`
  names the exact public hostname(s) this deployment is served under, and
  `TrustedForwardedHostFilter` then rejects any request whose effective host is not one of
  them. This is a defense-in-depth check, not a substitute for keeping the application port
  unreachable except through the intended proxy.
- The TDLib update listener dispatch queue (security-audit finding SEC-14) is bounded rather
  than the unbounded default a plain `Executors.newSingleThreadExecutor()` would give it: once
  full, further updates apply backpressure to the native receive loop itself instead of being
  dropped or accumulating without limit, so a busy account or a slow listener cannot exhaust
  memory.
- `McpTools.guarded` (security-audit finding SEC-15) now attributes a rate-limit rejection to
  the chat(s) the raw request named even though that happens before argument parsing runs, and
  inspects the returned `CallToolResult`'s error flag so a structured error result is recorded
  as `ERROR` rather than `ALLOWED`; a generic runtime failure is now also recorded as a
  structured `ERROR` audit event rather than only logged.
- `ChatActivationService.deleteStoredData` (security-audit finding SEC-16) no longer resets the
  sync checkpoint, so a chat left `ACTIVE` after an explicit data deletion does not have its
  erased history silently re-backfilled by the next sync; only genuinely new messages are
  captured going forward. The management UI now describes this action and `Revoke` separately,
  rather than presenting both as an equally permanent "done with this chat for good" action.
- OAuth access tokens survive a signing-key rotation (security-audit finding SEC-17):
  `tgmcp.secrets.retiring-oauth-verification-keys` adds a previous key's public half to the JWK
  set for verification only, so a token signed before rotation keeps validating through its
  configured overlap window, while every new token is still signed only with the current key --
  a verification-only entry has no private key and so can never be selected to sign.
- `.github/workflows/ci.yml` (security-audit finding SEC-18) now runs the full test suite
  against a real PostgreSQL service container on every push and pull request, and a separate
  job builds the release Docker image only once that suite has passed. Dependabot
  (`.github/dependabot.yml`) watches Maven, Docker base image, and Action dependencies weekly.
- "Sign in with Telegram" (`TelegramLoginController`) stays hidden -- the link is not rendered
  on the sign-in or sign-up page -- until both `tgmcp.telegram-login.bot-token` and
  `bot-username` are configured, the same off-by-default posture the `TDLIB` Telegram adapter
  itself uses. The callback verifies Telegram's HMAC-SHA-256 signature over the exact
  data-check-string Telegram's login-widget specification defines, using a constant-time
  comparison, and separately rejects a callback whose `auth_date` is more than ten minutes old
  so a captured callback URL cannot be replayed indefinitely. It never embeds Telegram's login
  widget JavaScript -- only a plain redirect to `oauth.telegram.org` and back -- so the
  management UI's `script-src 'self'` Content-Security-Policy is unchanged by this feature.
  Self-registration and the Telegram callback each sit behind their own IP-keyed rate limiter
  (`tgmcp.abuse-controls.self-registrations-per-hour-per-ip`, and the existing
  `login-attempts-per-minute-per-ip` for the callback), on the same footing as sign-in and
  OAuth token issuance (security-audit finding SEC-10).
