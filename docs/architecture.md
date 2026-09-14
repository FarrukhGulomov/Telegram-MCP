# Architecture

A modular monolith. One deployable, one database, strict package boundaries. There is no
reason for this system to be several services, and several services would multiply the number
of places an authorization check could be forgotten.

## Packages

```
io.tgmcp
├── tenancy/      Tenant, AppUser, AuthenticatedActor — identity every decision is made against
├── security/     OAuth2 authorization server, resource server, RFC 9728 metadata, scopes
├── consent/      Consent records, coverage computation
├── chat/         Chats, participants, activation lifecycle
├── access/       ChatAccessService — the single authorization decision point
├── telegram/
│   ├── gateway/  TelegramGateway port + DTOs + exception hierarchy (read-only by construction)
│   ├── mock/     Synthetic adapter (default)
│   ├── tdlib/    Real adapter; native code confined to NativeTdlibClient
│   └── session/  AES-256-GCM envelope for session material
├── sync/         Checkpoints, bounded backfill, incremental catch-up, backoff, retention
├── message/      Storage and PostgreSQL full-text search
├── mcp/          Tool definitions, schemas, cursors, response envelopes
├── ui/           Thymeleaf management UI
├── audit/        Append-only access metadata
├── ratelimit/    Per-caller token bucket
└── config/       Properties, adapter selection, demo seeding
```

## The two rules that shape everything

**1. One authorization decision point.** `ChatAccessService` answers "may this caller read
this chat right now?". MCP tools, UI controllers and the sync worker all call it. Nothing
reaches `MessageRepository` for content without having gone through it. Adding a sixth tool
means calling one method, not re-implementing a policy.

**2. Read-only is structural, not conventional.** `TelegramGateway` has no send, edit, delete
or join operation. A future adapter cannot expose a write path through this port without
changing the interface — which is a visible, reviewable act rather than an oversight.

## Request path for an MCP tool call

```
POST /mcp
  │
  ├─ Spring Security (chain @Order 2): validate JWT — signature, issuer, audience, expiry, scope
  │
  ├─ WebMvcStatelessServerTransport.contextExtractor  ← runs on the servlet request thread,
  │     where SecurityContextHolder is populated; packs the actor and provider into
  │     McpTransportContext so the tool handler never depends on thread affinity
  │
  ├─ McpTools.<tool>
  │     ├─ rate limit
  │     ├─ parse and validate arguments  (inside the error handler, so a bad argument
  │     │                                 becomes INVALID_ARGUMENT, not a protocol error)
  │     ├─ ChatAccessService.authorize   ← consent, activation, provider, sync state
  │     ├─ MessageRepository query       ← tenant-scoped, parameterised, keyset-paged
  │     ├─ ChatAccessService.reverify    ← again, before anything is serialised
  │     └─ ToolResponses.success         ← byte cap, truncation, coverage, freshness
  │
  └─ AuditService.record                 ← metadata only
```

The double authorization check is the part worth noticing. Checking once before the query
leaves a window in which consent is withdrawn while rows are in flight. Checking again after
the read closes it, and costs one cheap query.

## Why stateless MCP

`spring.ai.mcp.server.protocol=stateless`. Every request carries its own bearer token, so
identity and authorization are re-established per call rather than captured once at session
setup. A revocation therefore takes effect on the next request instead of at the next
reconnect. It also means no session affinity, which matters if this is ever run behind more
than one instance.

## Why PostgreSQL full-text search, and nothing else

`messages.content_tsv` is a generated `tsvector` column with a GIN index, queried through
`websearch_to_tsquery`. One row is the only copy of a message. Deleting it removes the message
from storage and from search in the same statement — there is no cache and no vector index to
forget about. That is a retention property, not just a simplicity one, and it is why Redis and
a vector database are deliberately absent from the MVP.

The trade-off is real: the text search configuration is `english`, so stemming is wrong for
other languages, and there is no semantic search. Both are acceptable for an MVP whose job is
to return evidence for a model to analyse; neither should be papered over.

## Pagination

Keyset, not offset: `(sent_at, chat_id, tg_message_id)` for search, `(sent_at, tg_message_id)`
for history, using PostgreSQL row-value comparison. Stable under concurrent inserts. The
position travels in an HMAC-signed opaque cursor bound to tenant, user, tool, chat scope,
query and range, so it cannot be replayed into a different request.

## Failure classification in sync

The gateway exception hierarchy forces every adapter to say whether a failure is worth
retrying:

| Exception | Retryable | Sync behaviour |
|---|---|---|
| `TelegramFloodWaitException` | yes | Wait exactly as long as Telegram asked |
| `TelegramTransientException` | yes | Bounded exponential backoff, halt after N failures |
| `TelegramAuthException` | no | Halt immediately |
| `TelegramPermissionException` | no | Halt immediately |

Getting this wrong in either direction is expensive: retrying a revoked session hammers
Telegram, and giving up on a network blip silently stops ingestion.
