# Connecting Claude and ChatGPT

This server is a **remote MCP server** speaking Streamable HTTP at `/mcp`, protected as an
OAuth 2.1 resource server. It follows MCP specification revision **2025-11-25** for
authorization.

> Not yet verified against live Claude or ChatGPT. The flow below is what the server
> implements and what the specification requires of clients; the server side is exercised
> end to end by the official MCP Java SDK client in the test suite. See
> [production-prerequisites.md](production-prerequisites.md).

## What the server publishes

| Endpoint | Purpose |
|---|---|
| `POST /mcp` | Streamable HTTP MCP endpoint (stateless) |
| `GET /.well-known/oauth-protected-resource` | RFC 9728 Protected Resource Metadata |
| `GET /.well-known/oauth-protected-resource/mcp` | Same, at the endpoint-path form clients also try |
| `GET /.well-known/oauth-authorization-server` | RFC 8414 Authorization Server Metadata |
| `POST /oauth2/token`, `GET /oauth2/authorize` | OAuth 2.1 with PKCE |
| `POST /connect/register` … | RFC 7591 Dynamic Client Registration (see the metadata document for the exact path) |

An unauthenticated call to `/mcp` returns `401` with:

```
WWW-Authenticate: Bearer resource_metadata="https://your-host/.well-known/oauth-protected-resource",
                  scope="mcp:read provider:anthropic_claude"
```

which is how a client discovers where to authenticate.

Check what your deployment actually publishes:

```bash
curl -s https://your-host/.well-known/oauth-protected-resource | jq
curl -s https://your-host/.well-known/oauth-authorization-server | jq
curl -si -X POST https://your-host/mcp | head -3
```

## Scopes

| Scope | Meaning |
|---|---|
| `mcp:read` | Required for every call. Without it the request is rejected at the filter chain. |
| `provider:anthropic_claude` | The token acts for Claude |
| `provider:openai_chatgpt` | The token acts for ChatGPT |

A token must carry `mcp:read` **and** exactly one provider scope. The provider scope is what
matches against each chat's approved provider scope, so a chat activated only for Claude is
invisible to a ChatGPT token. A token with no provider scope authorizes nothing.

## Before you connect

The server exposes only chats that are `ACTIVE` with complete participant consent for the
calling provider. If you connect and `list_allowed_chats` returns nothing, that is the
expected state until you have gone through the management UI: connect Telegram → refresh the
chat list → select a chat → record consent for every participant → activate.

## Claude

1. Deploy behind HTTPS with `TGMCP_OAUTH_ISSUER`, `TGMCP_PUBLIC_BASE_URL` and
   `TGMCP_MCP_RESOURCE` set to the public URLs (for example `https://mcp.example.com`,
   `https://mcp.example.com`, `https://mcp.example.com/mcp`).
2. In Claude, add a custom connector / remote MCP server with the URL
   `https://mcp.example.com/mcp`.
3. Claude discovers the authorization server from the metadata above, registers dynamically
   (RFC 7591) or uses a pre-registered client, and runs authorization code + PKCE.
4. Sign in with your management-UI credentials at the consent screen. The issued token is
   bound to your tenant and user, and carries `provider:anthropic_claude`.

A demo client `claude-demo` is pre-registered by the seeder with redirect URI
`https://claude.ai/api/mcp/auth_callback`. Confirm the redirect URI Claude actually sends and
adjust it; do not assume the one in the seeder is current.

## ChatGPT

1. Deploy as above.
2. Add the server as a connector with URL `https://mcp.example.com/mcp`.
3. Complete the same OAuth flow. The issued token carries `provider:openai_chatgpt`.

A demo client `chatgpt-demo` is pre-registered with redirect URI
`https://chatgpt.com/connector_platform_oauth_redirect`. Same caveat.

## Local testing without a provider

The test suite drives the server with the official MCP Java SDK client; `McpTestClient` and
`TestTokens` in `src/test/java/io/tgmcp/support` show the whole pattern, including how a token
is minted with the right issuer, audience, scopes and tenant binding.

## The tools

| Tool | Arguments | Notes |
|---|---|---|
| `list_allowed_chats` | none | Start here. Only activated, fully consented chats appear. |
| `search_messages` | `chat_ids`, `query`, `from`, `to`, `cursor?`, `limit?` | Full-text search. `from`/`to` are required. |
| `get_chat_history` | `chat_id`, `from`, `to`, `cursor?`, `limit?` | Chronological, paged. |
| `get_message_context` | `chat_id`, `message_id`, `before?`, `after?` | Bounded window around one message. |
| `get_sync_status` | `chat_id` | Coverage, freshness, backfill state. Consult before concluding something is absent. |

All five declare `readOnlyHint: true`. Message ids are strings — Telegram ids exceed what a
JSON number safely represents.

### Response shape

Every content-bearing response carries:

- `items` — the results, each with `chatId`, `messageId`, `sentAt`, `sender`, `text`, `link`
  (null when no valid public link exists — never fabricated) and `source`;
- `dataHandling` — an explicit statement that message text is untrusted third-party content;
- `coverage` / `coverageByChat` — approved range, retention, what is actually stored, whether
  backfill finished;
- `freshness` — when the chat last synchronised successfully;
- `pagination` — opaque cursor, `hasMore`, page size;
- `truncation` — whether anything was dropped, and why.

### Error codes

| Code | Meaning |
|---|---|
| `ACCESS_DENIED` | No accessible chat matches. Deliberately identical for unknown, other-tenant, never-activated and revoked chats. |
| `CONSENT_REQUIRED` | The chat was opted in by your tenant, but participant consent is incomplete or uncertain. |
| `SYNC_PENDING` | Approved, but nothing has synchronised yet. |
| `INVALID_ARGUMENT` | Bad arguments, or a cursor that does not belong to this request. |
| `RATE_LIMITED` | Per-caller budget exhausted. |
| `INTERNAL_ERROR` | Something failed; details are in the server log, not the response. |
