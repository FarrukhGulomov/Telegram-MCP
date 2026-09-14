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
full suite: **139 tests**, ~35 seconds. See [Test results](#test-results) below for what's
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

139 tests, all passing, run against real PostgreSQL 16 (not H2/mocked):

| Suite | Count | Covers |
|---|---|---|
| `McpProtocolTests` | 16 | Real MCP SDK client: init, discovery, tool calls, auth rejections (bad/expired/wrong-audience/wrong-issuer/no-scope tokens) |
| `AccessPolicyTests` | 18 | Cross-tenant denial, unknown-vs-unauthorized indistinguishability, every consent gap, provider scoping, window clamping |
| `SyncBehaviourTests` | 16 | Idempotent ingestion, restart recovery, edits/deletions, membership-change pausing, flood-wait, exponential backoff, halt-on-auth-failure |
| `PaginationAndLimitsTests` | 16 | Full pagination walk, 5 cursor-tampering variants, every input cap |
| `RetentionAndRevocationTests` | 10 | Immediate access block, idempotent purge, mid-request revocation race, retention sweep removing rows from search |
| `EndToEndMockFlowTests` | 4 | The full UI+MCP journey; CSRF rejection; XSS-escaping of Telegram-controlled titles |
| `UntrustedContentTests` | 6 | The seeded prompt-injection message stays inert data in every response |
| `CursorCodecTests`, `AudienceValidatorTests`, `KeyMaterialTests`, `RateLimitAndResponseCapTests`, `TdlibTelegramGatewayTests`, `TdlibErrorTranslationTests`, `BackoffTests` | 63 | Unit-level: cursor binding/tampering, RFC 8707 audience matching, key-length validation, rate limiting, response byte caps, TDLib JSON mapping, error classification |

No test result here is asserted without having actually been run in this environment; the
numbers above are from the run in this session's build log.

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
