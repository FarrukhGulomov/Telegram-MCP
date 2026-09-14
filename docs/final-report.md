# Final report

## What was built

A working Spring Boot MVP implementing every required piece of the spec: a modular monolith
exposing a read-only MCP server (Streamable HTTP, OAuth 2.1) over Telegram chats gated by an
explicit consent model, a server-rendered management UI, PostgreSQL + Flyway storage with
full-text search, a synthetic mock adapter and a real TDLib adapter behind the same
interface, Docker Compose, and 139 passing tests run against real PostgreSQL.

## Implemented features, against the spec's sections

**Product scope (§2):** read-only MCP over explicitly activated chats; message search,
bounded history, contextual retrieval; no LLM API key required to run the core service; no
send/edit/delete/join anywhere in the codebase (`TelegramGateway` has no such method); Secret
Chats, media download, OCR, transcription and vector search excluded.

**Consent (§3):** three separate authorizations (connect account / activate chat /
participant consent), never conflated; consent is explicit, revocable, scoped to purpose +
provider + version; an admin cannot silently consent for someone else (`SIGNED_ATTESTATION`
requires an evidence reference); coverage recomputed on every request, not cached; membership
change or purpose/provider change forces renewed review; no content retained while a chat
awaits activation. See `docs/consent-model.md`.

**Technology (§4):** Java 21, Spring Boot 4.1.1, official MCP Java SDK 2.0.1 via Spring AI's
MCP starter, Streamable HTTP, PostgreSQL 16 + Flyway, TDLib for the real adapter with native
code isolated in `io.tgmcp.telegram.tdlib`, Docker Compose, modular monolith, server-rendered
(Thymeleaf) management UI, PostgreSQL full-text search (no Redis, no vector DB).

**Architecture (§5):** separate packages for auth/tenancy, consent, Telegram adapter/sync,
message storage/search, MCP tools, UI/audit; `TelegramGateway` interface with `MockTelegramGateway`
and `TdlibTelegramGateway`; `ChatAccessService` is the single enforcement point every access
path (MCP, UI, sync worker) calls through.

**Auth (§6):** Spring Authorization Server (no hand-rolled OAuth or token crypto); JWT
validated for issuer, audience (RFC 8707), expiry, scope; tenant/user derived from validated
token claims only, never tool arguments; MCP tokens and Telegram session credentials are
different objects in different tables; TDLib login states including QR and 2FA password
supported; codes/passwords/QR tokens never logged or exposed to MCP tools; session material
sealed with AES-256-GCM, key outside the database; the UI states plainly that a connected
session is broader than read-only chat access; disconnect flow explains revoking inside
Telegram itself.

**Onboarding/UI (§7):** sign-in with a demo-mode banner; connect + status screens; private
per-user chat selection; per-chat consent status and activation controls; approved range and
retention settings; sync health, last-success, partial-history indicators; pause/disconnect/
revoke/delete-data actions; each chat page states which provider receives message text and
that disclosed data cannot be recalled from it; coverage is never overstated.

**MCP tools (§8):** exactly the five specified tools, all `readOnlyHint: true` and read-only
in implementation; explicit bounded date ranges required; HMAC-signed opaque cursors bound to
tenant/user/tool/scope/query/range; page size, context size, query length, chat count and
response bytes all capped server-side to match the published schema; structured results with
chat/message id, timestamp, source metadata; links only when genuinely valid, never
fabricated; coverage/truncation/pagination/freshness on every response; `ACCESS_DENIED` /
`CONSENT_REQUIRED` / `SYNC_PENDING` used and indistinguishable for unknown-vs-unauthorized;
message text is only ever a data field, never treated as instructions.

**Sync/storage (§9):** only active allowlisted chats within their approved range; bounded
backfill + incremental catch-up; persisted checkpoints; idempotent ingestion (duplicates,
edits, deletions, restarts all handled — see `SyncBehaviourTests`); flood-wait honored
exactly, bounded exponential backoff otherwise, auth/permission failures halt immediately
rather than retrying; every table and query is tenant-scoped; retention applied to messages
(no separate cache/index to forget, since search is a generated column on the same row);
consent and authorization rechecked before every response; revocation blocks reads
immediately, purge follows idempotently; residual-data and backup limitations documented
plainly rather than glossed over.

**Security (§10):** cross-tenant access structurally prevented (every query is
tenant-parameterized; verified in tests); parameterized queries throughout (no string-built
SQL); CSRF on the UI chain, none needed on the stateless bearer-token MCP chain; Thymeleaf
escapes all Telegram-derived text (tested against literal `<script>` content); no attachment
execution or automatic URL fetching exists to be triggered; no generic SQL/filesystem/shell/
HTTP-fetch/Telegram-method tool is exposed; secrets and message text kept out of logs and
audit rows; per-caller rate limiting and bounded sync concurrency; sensitive chats stay
inactive until deliberately activated (demonstrated by a synthetic "Leadership (sensitive)"
chat that never gets touched by any test); read-only MCP is explicitly *not* claimed to
eliminate exfiltration risk (`docs/threat-model.md` says so directly).

**Tests (§11):** all eleven required categories are covered — see the table in `README.md`
and the per-file breakdown in `docs/threat-model.md`. The full mock flow (sign in → select →
consent → activate → sync → search → revoke → deny) runs as `EndToEndMockFlowTests`, driven
through real HTTP endpoints and a real MCP client, not simulated.

## Test results (this session, actually run)

```
139 tests, 0 failures, 0 errors — mvn test, against PostgreSQL 16
```
Verified live in this environment; not a claim carried over from a prior run. The full suite
output is reproducible with `./scripts/run-tests.sh`.

## What was NOT verified, stated plainly

- **No live Telegram account was connected.** The TDLib adapter's JSON translation is unit
  tested against a scripted client; the native transport (`libtdjson`, JNA loading, the
  `td_receive` loop, live authorization states) has not been exercised. No native library was
  available in this build environment, and connecting a real account was outside this
  session's authorization regardless.
- **No live Claude or ChatGPT session was connected.** The MCP server is verified end to end
  with the official MCP Java SDK client (`McpProtocolTests`) — real Streamable HTTP,
  real OAuth token validation, real tool calls — but not with an actual Claude or ChatGPT
  client.
- **Telegram's API Terms and Content Licensing terms were not read during this work.**
  Outbound access to `core.telegram.org` was blocked in this environment. I did not
  substitute a from-memory summary in the one place inaccuracy would matter most; instead
  `docs/production-prerequisites.md` §1 lists the exact questions to resolve before
  connecting a real account, with links to read.
- **RFC 8707 resource-parameter negotiation is partial.** Tokens carry and are checked
  against this server's canonical resource identifier as their audience (enforced), but the
  authorization server does not yet negotiate a per-request `resource` parameter — Spring
  Authorization Server 7.1 has no first-class support for it yet.
- **TDLib edit/delete revisions are not wired into the sync worker** (`fetchRevisions`
  returns empty for the TDLib adapter — TDLib pushes these as updates rather than exposing a
  queryable history). The mock adapter implements and tests this path fully; the real adapter
  does not yet.
- **No production-hardening**: ephemeral JWT signing key (rotates every restart by design in
  dev), open dynamic client registration, single-node rate limiter and scheduler assumptions,
  no secret-manager integration. All listed with the reasoning in
  `docs/production-prerequisites.md` §3.
- Legal/compliance conclusions (lawful basis, controller/processor roles, cross-border
  transfer, special-category data) are explicitly left to counsel, not asserted by the code.

## Shortest path to run and evaluate

```bash
git clone <this-repo> && cd telegram-consent-mcp
./scripts/run-local.sh          # builds, starts Postgres + the app, seeds demo data
# open http://localhost:8080, sign in with owner@demo.invalid / demo-password
```

To run the tests instead of/as well as the app:

```bash
./scripts/run-tests.sh          # 139 tests, ~35s, real PostgreSQL
```

Or with zero local Java/Maven at all, via Docker Compose (needs only Docker):

```bash
cp .env.example .env && docker compose up --build
```

`scripts/dev-db.sh` (used by both scripts above) starts PostgreSQL via Docker when available
and falls back to a local install otherwise, so either path works in this build environment.
