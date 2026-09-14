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

**Blocking gap: the TDLIB adapter has no live edit/deletion pipeline.** `fetchRevisions()`
always returns an empty list for this adapter — not because nothing changed, but because
edits and deletions arrive from TDLib as pushed `updateMessageContent` / `updateDeleteMessages`
events rather than as a queryable history, and nothing in this codebase currently listens for
those events and turns them into revisions. A message edited or deleted on Telegram after
this service has ingested it will silently keep showing its original, stale content and will
never be marked deleted. The application logs a warning to this effect every time the TDLIB
adapter starts (see `TelegramConfiguration`). Building that pipeline means routing
`td_receive()` updates into the sync worker under the adapter's per-connection locking — the
same locking this security pass added for finding F01 — which is a distinct, larger piece of
work than the P0 fixes in this pass (tracked as security-audit finding F11) and is explicitly
out of scope here. **Do not deploy the TDLIB adapter for any chat where stale edits or
undetected deletions are a real risk until this is built.**

**Verified against live Claude or ChatGPT: no.** The MCP server is driven end to end by the
official MCP Java SDK client in the test suite (initialize, tools/list, tools/call, and the
authentication rejections). It has not been connected to Claude or ChatGPT. The
implementation follows MCP specification revision 2025-11-25 for authorization; confirm
against the revision your clients negotiate.

Also outstanding:

- **RFC 8707 resource indicators are only half-implemented.** Access tokens carry this
  server's canonical resource identifier as their audience, and the resource server enforces
  it. The authorization server does not yet negotiate the `resource` request parameter per
  request, so a client asking for a narrower or different resource is not honoured — it is
  ignored. Spring Authorization Server 7.1 has no first-class support for this.
- **The token signing key is ephemeral.** A new RSA key is generated at startup, so every
  restart invalidates outstanding tokens. Production needs a persisted, rotatable key in a
  KMS or HSM.
- **Dynamic client registration is open.** The RFC 7591 endpoint accepts any registration.
  Production needs registration policy: allowed redirect URIs, allowed scopes, approval, and
  rate limiting.
- **The provider a token authorizes is carried in a scope** (`provider:anthropic_claude`).
  That is derived from validated authentication, but it is only as trustworthy as your
  client-registration policy. Tighten (3) before relying on (this).
- **No HTTPS termination, no CSRF-safe reverse-proxy configuration, no secret manager
  integration** is included.
- **Single-node assumptions**: the rate limiter is in-memory and the sync scheduler assumes
  one instance. Running more than one replica needs a shared limiter and either leader
  election or per-chat locking.
- **Backups.** See [retention-policy.md](retention-policy.md).
- **Participant notification.** Nothing here tells participants their chat is being
  processed. That is an operational obligation.

## 4. Safety interlocks currently in place

- The default adapter is `MOCK`; no Telegram account is contacted.
- The `TDLIB` adapter refuses to start unless
  `tgmcp.telegram.real-account-access-authorized=true` is set explicitly, *in addition* to
  supplying `api_id` and `api_hash`.
- Missing keys produce a derived development key and a loud warning rather than a silent
  weak default.
- Sensitive chats stay `INACTIVE` until someone activates them deliberately; the synthetic
  dataset includes one such chat to make the point.
