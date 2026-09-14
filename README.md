# Telegram Consent MCP

A consent-gated, read-only [Model Context Protocol](https://modelcontextprotocol.io) server
that lets Claude and ChatGPT search and analyze **explicitly authorized** Telegram team
chats — chats an operator has activated, whose participants have all recorded consent for
that exact purpose and provider.

Built for small teams. No message can be read by an MCP client until: the account owner
connected Telegram, an operator selected the chat and stated a purpose and provider scope,
and **every current participant** recorded consent for that context. Revoking access blocks
reads on the very next request and deletes the stored copy.

Ships in **fully synthetic demo mode by default** — no Telegram account, no credentials, no
LLM API key required to run, explore or test it.

```
sign in → connect Telegram → select a chat → collect consent → activate → sync
   → search over MCP → revoke → denied
```

That whole flow is exercised end to end in `EndToEndMockFlowTests` and takes about 8 seconds
to run.

## What this is not

- Not a bot, not a bulk-export tool, not a public-channel scraper.
- No message sending, editing, deletion, joining, or account changes anywhere in the code.
- No Secret Chats, no media download, no OCR, no transcription, no vector search.
- Not verified against a live Telegram account or against Claude/ChatGPT in production — see
  [docs/production-prerequisites.md](docs/production-prerequisites.md) for exactly what is
  and isn't proven.

## Quick start (2 minutes, no credentials)

Requires Java 21 and Maven. PostgreSQL is started for you — via Docker if available, or a
local install otherwise.

```bash
./scripts/run-local.sh
```

Then open <http://localhost:8080>, sign in with `owner@demo.invalid` / `demo-password`
(printed in the startup log), and walk the UI: **Connect** (QR login accepts any code in demo
mode) → **Refresh chat list** → pick a chat → **record consent** for each synthetic
participant → **Activate** → **Sync now**. Three synthetic conversations are preloaded,
including one that stays inactive until you deliberately activate it and one containing a
deliberate prompt-injection message, kept there specifically to prove it stays inert.

### Or with Docker Compose

```bash
cp .env.example .env
docker compose up --build
```

### Running the tests

```bash
./scripts/run-tests.sh
```

This starts a real PostgreSQL (search uses a generated `tsvector`/GIN index and pagination
uses row-value comparisons — an in-memory substitute would test something else) and runs the
full suite: **178 tests**, ~30 seconds. See [Test results](#test-results) below for what's
covered.

## Connecting Claude or ChatGPT

The server is a Streamable HTTP MCP endpoint at `/mcp`, protected as a standards-compliant
OAuth 2.1 resource server (Spring Authorization Server — no hand-rolled token cryptography).
It publishes RFC 9728 Protected Resource Metadata and RFC 8414 Authorization Server Metadata
so clients can discover how to authenticate, and validates issuer, audience, expiry and scope
on every token per the current MCP authorization specification (2025-11-25).

Full instructions, including the exact scopes and error codes: **[docs/mcp-clients.md](docs/mcp-clients.md)**.

> This has been exercised end to end with the official MCP Java SDK client (init, tool
> discovery, tool calls, auth rejections) — see `McpProtocolTests`. It has **not** been
> connected to a live Claude or ChatGPT session.

## The five tools

| Tool | Purpose |
|---|---|
| `list_allowed_chats` | Only chats that are active and fully consented for the caller's provider |
| `search_messages` | Full-text search across chats, bounded date range, opaque cursor paging |
| `get_chat_history` | Chronological messages in one chat, bounded range, paged |
| `get_message_context` | Bounded window of messages around one message |
| `get_sync_status` | Coverage, freshness, and backfill state for one chat |

All five are `readOnlyHint: true` and enforced read-only in code: the `TelegramGateway`
interface has no write, edit, delete, or join method to call. Every response carries an
explicit statement that message text is untrusted third-party data. See
[docs/mcp-clients.md](docs/mcp-clients.md) for full schemas and error codes.

## Architecture

Modular monolith: one Spring Boot application, one PostgreSQL database, strict package
boundaries, a single `ChatAccessService` that every access path — MCP tools, the management
UI, the sync worker — goes through. Full breakdown, request-path trace, and the reasoning
behind stateless MCP and PostgreSQL-only search: **[docs/architecture.md](docs/architecture.md)**.

```
io.tgmcp
├── tenancy/    identity           ├── telegram/gateway/  read-only port
├── security/   OAuth2 + RFC 9728  ├── telegram/mock/     synthetic adapter (default)
├── consent/    coverage           ├── telegram/tdlib/    real adapter, native code isolated
├── chat/       activation         ├── sync/       checkpoints, backoff, retention
├── access/     ★ the one gate     ├── message/    storage + full-text search
├── mcp/        tools & cursors    ├── ui/         Thymeleaf management UI
└── audit/      access metadata    └── config/     adapters, demo seeding
```

## Consent, threat model, retention

Three separate authorizations — connecting an account, activating a chat, and each
participant's consent — are tracked independently and none implies another. An administrator
cannot silently consent for someone else; expiring, revoking, or a membership change pauses
access automatically and is re-checked on every single request, not cached from activation
time.

- **[docs/consent-model.md](docs/consent-model.md)** — the model, the lifecycle, what it does
  and doesn't achieve.
- **[docs/threat-model.md](docs/threat-model.md)** — twelve threats, the code that addresses
  each, and an explicit list of what is *not* protected against.
- **[docs/retention-policy.md](docs/retention-policy.md)** — what's stored, when it's
  deleted, and the honest limits (backups, VACUUM, disclosed-data recall).
- **[docs/production-prerequisites.md](docs/production-prerequisites.md)** — Telegram terms
  questions I could not resolve (network access to `core.telegram.org` was blocked while
  building this — see below), legal prerequisites, and unfinished engineering work.
- **[docs/tdlib-setup.md](docs/tdlib-setup.md)** — building and wiring the real TDLib
  adapter, and its known gap (edit/delete revisions are not yet wired into the sync worker).

## The real TDLib adapter, and the blocker around it

`io.tgmcp.telegram.tdlib` binds TDLib's current JSON interface (`td_create_client_id`,
`td_send`, `td_receive`, `td_execute` — the deprecated `td_json_client_*` functions are not
used) via JNA, isolated behind the same `TelegramGateway` port the mock adapter implements.
Its JSON-to-domain translation is covered by 13 unit tests against a scripted fake client,
built from the current `td_api.tl` schema and `td_json_client.h` header in the `tdlib/td`
repository.

**What I could not do, and why:** this build environment has no `libtdjson` binary, no
Telegram credentials, and — this being unattended, unauthorized development work — no
authorization to connect a real account even if it did. So the native transport itself
(`NativeTdlibClient`, the JNA binding, the `td_receive` loop) has not been exercised, and I am
not claiming it has. `TelegramConfiguration` refuses to start the TDLIB adapter at all unless
`tgmcp.telegram.real-account-access-authorized=true` is set explicitly — a safety interlock,
not an oversight. The default adapter, `MOCK`, needs none of this and is what everything above
runs against.

Separately: outbound access to `core.telegram.org` was blocked in this environment, so I
could not read Telegram's current API Terms or Content Licensing terms directly. Rather than
summarize them from memory in the one place that would be most damaging to get wrong, I
listed the specific questions that need answering in
[docs/production-prerequisites.md](docs/production-prerequisites.md) §1.

## Test results

178 tests, all passing, run against real PostgreSQL 16 (not H2/mocked). This count reflects a
security audit (see below) that added regression coverage for ten P0 findings on top of the
original suite:

| Suite | Count | Covers |
|---|---|---|
| `McpProtocolTests` | 16 | Real MCP SDK client: init, discovery, tool calls, auth rejections (bad/expired/wrong-audience/wrong-issuer/no-scope tokens) |
| `AccessPolicyTests` | 18 | Cross-tenant denial, unknown-vs-unauthorized indistinguishability, every consent gap, provider scoping, window clamping, an immutable-context-revision reversion case |
| `SyncBehaviourTests` | 21 | Idempotent ingestion, restart recovery, edits/deletions, membership-change pausing, flood-wait, exponential backoff, halt-on-auth-failure, PAUSED-chat ingestion refusal, concurrent-revoke fencing (incl. the revoke-then-reactivate ABA case) |
| `PaginationAndLimitsTests` | 16 | Full pagination walk, 5 cursor-tampering variants, every input cap |
| `RetentionAndRevocationTests` | 10 | Immediate access block, idempotent purge, mid-request revocation race, retention sweep removing rows from search |
| `EndToEndMockFlowTests` | 4 | The full UI+MCP journey; CSRF rejection; XSS-escaping of Telegram-controlled titles |
| `UntrustedContentTests` | 6 | The seeded prompt-injection message stays inert data in every response |
| `TelegramConnectionIsolationTests` | 3 | Single-active-owner guard on the TDLIB adapter, no-op under MOCK |
| `ConsentMethodGuardTests` | 5 | Self-service consent always rejected; demo-synthetic consent rejected once a real adapter is in play |
| `ConsentContextRevisionTests` | 2 | Widening retention alone invalidates consent; reverting to an old context never resurrects its old consent |
| `DynamicClientRegistrationTests` | 3 | Open DCR accepts `mcp:read`, rejects a self-declared `provider:*` scope, still requires `redirect_uris` |
| `TelegramConfigurationTests`, `ProductionSafetyValidatorTests` | 11 | TDLIB startup warns about the missing edit/delete pipeline; real-account startup refuses every unsafe demo/secret/HTTPS combination |
| `ChatFormValidationTests`, `ForwardedHeaderRedirectTests`, `LocalizationTests` | 9 | Chat form validation errors surface in the UI instead of a raw 400; reverse-proxy header handling; every enum-backed label resolves in all three locales |
| `CursorCodecTests`, `AudienceValidatorTests`, `KeyMaterialTests`, `RateLimitAndResponseCapTests`, `TdlibTelegramGatewayTests`, `TdlibErrorTranslationTests`, `BackoffTests` | 54 | Unit-level: cursor binding/tampering, RFC 8707 audience matching, key-length validation, rate limiting, response byte caps, TDLib JSON mapping (incl. multi-page membership pagination), error classification |

No test result here is asserted without having actually been run in this environment; the
numbers above are from the run in this session's build log.

## Security audit (2026-09)

A senior architecture/security/product/DevOps audit against this codebase produced twenty
findings (F01–F20), ten of them P0 (blocking). All ten P0 findings are fixed, each as its own
commit with its own regression test:

| ID | Risk | Fix | Files | Verified by |
|---|---|---|---|---|
| F01 | The single process-wide TDLib session let a second tenant/owner silently take over an in-flight real-account connection | Hard single-active-owner guard before `beginLogin()` on the TDLIB adapter (no-op under MOCK) | `TelegramConnectionRepository`, `TelegramConnectionService` | `TelegramConnectionIsolationTests` (3) |
| F02 | `SELF_SERVICE_LINK` consent had no participant-identity verification behind it; `DEMO_SYNTHETIC` consent could be recorded against a real account | Both methods rejected outright — the first has no real flow to fall back to, the second only ever valid under the MOCK adapter | `ConsentService`, `chat.html` | `ConsentMethodGuardTests` (5) |
| F03 | Reverting settings to values that matched an old, still-valid consent record silently resurrected that old consent; retention widening didn't invalidate anything | Immutable `contextRevision` (fresh random UUID) stamped on every activation and consent record; consent only ever covers the exact revision it was granted under | `ChatActivation`, `ConsentRecord`, `ConsentService`, `ChatActivationService`, `V3` migration | `ConsentContextRevisionTests` (2), `AccessPolicyTests` |
| F04 | Supergroups over 200 members silently reported only the first page; a transient empty membership response was treated as "nothing changed" | Paginated `listParticipants()` (capped at 5000); an empty response after a known non-empty membership now pauses instead of being ignored | `TdlibTelegramGateway`, `ChatSyncService` | `TdlibTelegramGatewayTests`, `SyncBehaviourTests` |
| F05 | A participant's messages kept circulating after they left without ever having valid consent for the chat's current context | Their messages are purged the moment their departure is detected, unless they hold valid consent for the current context revision | `ChatParticipantRepository`, `MessageRepository`, `ChatSyncService` | `SyncBehaviourTests` |
| F06 | A PAUSED chat could still ingest new content via the always-reachable manual "Sync now" action | Only an ACTIVE chat with verified-complete coverage may fetch new content; every other retainable state stops before fetching | `ChatSyncService` | `SyncBehaviourTests` |
| F07 | A revoke landing mid-fetch (including revoke-then-reactivate) had no way to stop content already fetched but not yet stored | Monotonic `epoch` fencing token, bumped only by revoke; re-checked before every commit inside a sync run | `ChatActivation`, `ChatActivationRepository`, `ChatActivationService`, `ChatSyncService`, `V4` migration | `SyncBehaviourTests` |
| F08 | A comment claimed the TDLIB adapter's missing edit/delete pipeline was already handled by a nonexistent class | False comment replaced with an accurate one; loud startup warning; documented as a blocking gap | `TdlibTelegramGateway`, `TelegramConfiguration`, `docs/production-prerequisites.md` | `TelegramConfigurationTests` |
| F09 | Open dynamic client registration let any anonymous caller self-declare a `provider:*` scope, letting an impostor client claim to be Claude or ChatGPT | Registration validator now rejects any self-declared `provider:*` scope; `mcp:read` remains open | `AuthorizationServerConfig` | `DynamicClientRegistrationTests` |
| F10 | Nothing stopped starting with real Telegram access authorized alongside demo seeding, dev-derived keys, or plain-HTTP OAuth/cookies | Fail-fast `ProductionSafetyValidator`, a no-op unless real access is authorized, then refuses to start with every unsafe setting listed at once | `ProductionSafetyValidator` (new) | `ProductionSafetyValidatorTests` (10) |

**Remaining risk after F01–F10:** F11–F20 (P1 code-quality/product findings from the same
audit) are analyzed but not yet fixed — notably F11 (the native TDLib receive loop can
deadlock synchronous requests against itself, which is also what blocks building the
edit/delete pipeline F08 documents as missing) and F12 (real-adapter history pagination can
skip messages across a large backlog). Separately, the audit's platform/legal-risk section
flags that Telegram's API Terms §1.5 and Content Licensing terms narrowly restrict AI-purpose
use and require individual, informed, ongoing, context-limited consent from every participant
— account-owner consent alone is not sufficient, and this has not been reviewed with counsel.
See [docs/production-prerequisites.md](docs/production-prerequisites.md) §1 for the specific
unresolved questions.

## Configuration reference

All settings are environment variables with safe local-dev defaults — see
[.env.example](.env.example) for every variable and what it does. Nothing in the example file
is a real secret. Generate real keys with:

```bash
./scripts/generate-keys.sh
```

## Stack (versions pinned in `pom.xml`)

Java 21 · Spring Boot 4.1.1 · Spring AI MCP 2.0.1 (MCP Java SDK 2.0.1) · Spring
Authorization Server 7.1.1 · PostgreSQL 16 + Flyway 12 · Thymeleaf · JNA 5.19.1 (TDLib
binding) · JUnit 5 + AssertJ + Mockito. Every dependency version was verified against Maven
Central at build time, not assumed.
