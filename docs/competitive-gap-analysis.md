# Competitive gap analysis

An external competitive review (2026-09-17) compared this project against several public
Telegram MCP implementations and made a set of factual claims about both this repository and
the market. This document audits those claims against the actual code, one by one, so that
positioning and engineering priorities are based on what is actually true today rather than on
what any document — including this one — asserts.

Classification key:

- **IMPLEMENTED** — the code exists, does what is claimed, and there is no known gap.
- **PARTIAL** — the core mechanism exists but with a specific, named limitation.
- **MISSING** — the capability does not exist, or the claim is not supported by evidence.
- **NEEDS_REAL_E2E** — the code exists and looks correct, but has never been exercised against
  a real, live external system (a real Telegram account, or a real ChatGPT/Claude connector).

Verified this session: `mvn -q -o test` against a real local PostgreSQL 16 — **291 tests across
42 test classes, 0 failures, 0 errors, 0 skipped.**

## 1. Java 21 / Spring Boot modular monolith — IMPLEMENTED

`pom.xml` pins Java 21 and Spring Boot 4.1.1 as a single artifact (`telegram-consent-mcp`).
Package layout (`tenancy/ security/ consent/ chat/ access/ telegram/{gateway,mock,tdlib,session}/
sync/ message/ mcp/ ui/ audit/ ratelimit/ config/`) matches `docs/architecture.md:7-28` exactly.

## 2. PostgreSQL full-text search — IMPLEMENTED

`db/migration/V1__core_schema.sql:135` — a generated `content_tsv tsvector` column with a GIN
index; `MessageRepository.java:141` queries it via `websearch_to_tsquery('english', :query)`.
English-only stemming, no semantic search — matches the documented trade-off in
`architecture.md:76-86`.

## 3. Streamable HTTP MCP endpoint at `/mcp` — IMPLEMENTED

`McpServerConfiguration.java:56-66` builds a stateless `WebMvcStatelessServerTransport`;
`WebSecurityConfig.java:64-65` matches `/mcp/**` in its own filter chain.

## 4. OAuth 2.1 resource server — IMPLEMENTED

`WebSecurityConfig.java:70-84` — `.oauth2ResourceServer(...)` with JWT decoding and
scope-based authorization, using Spring's own libraries, not hand-rolled crypto.

## 5. RFC 9728 + RFC 8414 discovery — IMPLEMENTED

RFC 9728 protected-resource metadata: `WebSecurityConfig.java:72-83`, served at
`/.well-known/oauth-protected-resource`. RFC 8414 authorization-server metadata is
auto-published by Spring Authorization Server from `AuthorizationServerConfig.java`'s
configurer. Both library-provided.

## 6. Exactly five read-only MCP tools — IMPLEMENTED

`McpToolNames.java:4-8`: `list_allowed_chats`, `search_messages`, `get_chat_history`,
`get_message_context`, `get_sync_status`. All five registered with `readOnlyHint=true`
(`McpServerConfiguration.java:146`); no handler reaches a mutating repository method.

## 7. Provider scoping — IMPLEMENTED

`McpScopes.java:12-42` derives the provider strictly from granted OAuth scopes, never a tool
argument. `AuthorizationServerConfig.java:99-115` (finding F09) blocks a self-registered OAuth
client from claiming a `provider:*` scope for itself; `TrustedMcpClientSeeder.java` and
`TrustedProviderAugmentingClientRepository.java` are the only trusted, non-self-service paths
to that scope.

## 8. Tenant isolation — IMPLEMENTED

`ChatAccessService` — the single authorization decision point — takes `tenantId` on every
method and delegates only to tenant-scoped, parameterized repository queries
(`MessageRepository.java:19-20`). Cross-tenant behavior is exercised in
`AccessPolicyTests.java` and `PaginationAndLimitsTests.java`. Minor discoverability gap: no
file is literally named `TenantIsolationTests`, though coverage exists.

## 9. Explicit per-participant consent model — IMPLEMENTED

`ConsentRecord.java:20-35` is keyed by `participantTgUserId`, not by chat or owner.
`covers()` (lines 57-61) requires an exact match against the chat's current
`contextRevision`, so any change to purpose/provider/retention invalidates prior consent
(finding F03). `ChatSyncService.purgeMessagesFromUnconsentedAuthors` re-checks every known
participant's consent on every membership refresh, not only at activation time.

## 10. Retention policy + revocation logic — IMPLEMENTED

`RetentionService.apply()` is scheduled and deletes rows outside the approved window or past
`retentionDays`. Revocation actually cuts off access: `ChatSyncService.requireNotFenced()`
re-checks the activation's epoch/state immediately before every content commit mid-sync
(finding F07), and `ChatAccessService` re-verifies access again right before serialization.

## 11. Synthetic demo mode — IMPLEMENTED

`MockTelegramGateway` is the default adapter; the whole product — onboarding, consent, sync,
MCP, revocation, self-registration, Telegram login — is exercisable with zero Telegram
credentials.

## 12. TDLib adapter boundary/abstraction — IMPLEMENTED

`TelegramGateway.java` is a real, read-only-by-construction interface (19 methods, no
send/edit/delete/join method exists on the type at all) with two concrete implementations —
`MockTelegramGateway` and `TdlibTelegramGateway` — selected at config time by
`TelegramAdapterKind`. A genuine port/adapter boundary, not a facade over one implementation.

## 13. Automated test count — VERIFIED

**291 tests, 42 test classes, 0 failures, 0 errors** (real PostgreSQL 16, this session).
Matches the on-disk `target/surefire-reports` from the last commit exactly — the suite is
green, not stale.

## 14. Security findings F01–F20 / SEC-01–18 — PARTIAL

All 38 identifiers are traceable to real, specific artifacts. F01–F20 and SEC-01–17 appear as
in-code comments at the exact lines they describe. **SEC-12** (Postgres role separation) and
**SEC-18** (CI/release-pipeline gating) are real and enforced, but live in
`docs/production-prerequisites.md` / `docker-compose.yml` / `.github/workflows/ci.yml` rather
than as an application-code comment — a narrower, but still legitimate, form of "identifiable."

## 15. Prompt-injection regression tests — IMPLEMENTED

`UntrustedContentTests.java` (155 lines) is a purpose-built suite: injected text
(`"IGNORE ALL PREVIOUS INSTRUCTIONS"`) is asserted to reach the client only as a plain `text`
data field, never as `role`/`system`/`instructions`/`developer`; every content-bearing response
is asserted to carry `dataHandling.untrustedContent=true`; reading injected content is asserted
to trigger zero extra gateway calls and never to widen chat access.

## 16. Response caps / rate limits on MCP tool calls — IMPLEMENTED

Per-caller (tenant+user) token-bucket rate limiting (`RateLimiter.java`) wraps every MCP tool
call; a separate `ToolResponses` byte cap truncates oversized responses. Separate, smaller
limiters exist for login/OAuth/registration/connect surfaces (finding SEC-10).

## 17. Audit attribution / queryable trail — IMPLEMENTED

`AuditEvent` records tenant, actor, action, chat, outcome and timestamp for every
consent/revocation/sync/access-deny action; `AuditService.record` runs in its own
`REQUIRES_NEW` transaction so a row survives a caller's later rollback (finding F20); the
management UI exposes a tenant-scoped, queryable `/audit` view.

## 18. TDLib edit/delete synchronization — PARTIAL / NEEDS_REAL_E2E (fixed this session, unverified)

**The competitive review's claim was accurate at the time of review. The pipeline it called
for now exists in code; what remains is real-account verification, which this environment
cannot provide.**

- `TelegramGateway.fetchRevisions(...)` is a real interface method.
- `MockTelegramGateway` implements it for real: it tracks a per-chat `revisionMarks` map
  updated on every synthetic edit/delete and returns actually-changed messages.
- `TdlibTelegramGateway.fetchRevisions(...)` **used to unconditionally return `List.of()`** —
  TDLib delivers edits/deletions as pushed `updateMessageContent` / `updateDeleteMessages`
  events, not as a queryable history, and nothing previously turned those events into
  revisions.
- **Groundwork that already existed:** `NativeTdlibClient.onUpdate(...)` is a working,
  general-purpose, backpressured, multi-listener update-dispatch pipeline (finding SEC-14) —
  every TDLib push update not correlated to a pending request already reaches every
  registered listener safely off the native receive thread. `TdlibTelegramGateway` already
  subscribed to it, but its `handleUpdate` only recognized `updateAuthorizationState`; every
  other update type, including the two needed here, was silently dropped.
- **What this session built:** `handleUpdate` now dispatches by type. `updateMessageContent`
  re-fetches the full message via `getMessage` and maps it with the existing `toRemoteMessage`;
  `updateDeleteMessages` needs only the message id. Both are buffered per chat (a bounded,
  per-chat `ConcurrentLinkedDeque`, cleared on disconnect so a later login never inherits a
  previous account's queued revisions); `fetchRevisions(...)` drains and clears that buffer on
  each sync tick. No structural change to `ChatSyncService`, `TelegramGateway`, or
  `RemoteMessage` was needed — the pull contract was already generic enough, proven by the mock
  adapter's real implementation. Covered by new unit tests against a scripted TDLib client.
- **What is still open:** never exercised against `td_receive()` output from a real account
  (NEEDS_REAL_E2E), and the buffer is in-memory only, so a revision pushed but not yet drained
  does not survive a process restart. Both are named explicitly in
  `docs/production-prerequisites.md`, which also still gates real-account startup on
  `tgmcp.telegram.acknowledge-missing-edit-deletion-pipeline` for exactly this reason —
  "pipeline exists" is not the same claim as "pipeline proven."

## 19. Onboarding flow — IMPLEMENTED

Self-service registration (`RegistrationController` → `SelfRegistrationService`), Telegram-based
login (`TelegramLoginController`, HMAC-SHA-256 verified, 10-minute replay window, hidden until
configured), and a five-step connect wizard (`ConnectionController`: start/code/password/
discover/disconnect) covering both QR and phone-number paths with 2FA — all real, wired, and
covered by `SelfServiceAuthenticationTests`, `TelegramDisconnectLifecycleTests`,
`TelegramConnectionAsyncStateTests`, `ConnectionRateLimitTests`, `TelegramConnectionIsolationTests`.

## 20. OAuth/MCP metadata validated against ChatGPT/Claude — MISSING (as a validated claim)

The repository's own docs already say this plainly
(`production-prerequisites.md`, "Verified against live Claude or ChatGPT: no"). All test
evidence exercises the official MCP Java SDK client and Spring's own OAuth2/Authorization-Server
stack — not a real ChatGPT or Claude connector. `TrustedMcpClientSeeder` pre-registering OAuth
client rows named `claude`/`chatgpt` is a registration convenience, not evidence of a real
connector handshake. The underlying mechanism is implemented; the specific claim of external
validation is not supported by evidence.

## Bottom line

Of the 20 claims audited, 15 are fully implemented and verified in-repo, 4 are partial with a
narrow, named limitation (tenant-isolation test naming; SEC-12/18 documentation location;
TDLib edit/delete sync, now built but unverified against a real account), and 1 is a genuine
open gap: validated ChatGPT/Claude interoperability, which requires a live connector this
environment cannot provide. Both remaining real-account/real-connector verifications are the
next priority, tracked in `docs/production-prerequisites.md`.
