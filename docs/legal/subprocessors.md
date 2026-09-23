# Subprocessors — DRAFT

> **This is a draft, not a published disclosure, and not legal advice.** It lists only what this
> codebase and its current deployment actually integrate with — verify against the live
> deployment's actual configuration before publishing, and have counsel confirm whether each
> entry below is correctly classified as a "subprocessor" in your jurisdiction (an AI provider
> receiving content via a user-initiated tool call may be classified differently than a
> traditional processor, depending on your regulator's guidance). Placeholders are `[FILL IN]`.

## AI providers (receive message content, per-chat, only once the operator activates that chat)

| Provider | Role | Trigger |
|---|---|---|
| Anthropic (Claude) | Processes message text retrieved via an MCP tool call, under its own terms | Only for a chat the operator has activated with `ANTHROPIC_CLAUDE` in its provider scope |
| OpenAI (ChatGPT) | Processes message text retrieved via an MCP tool call, under its own terms | Only for a chat the operator has activated with `OPENAI_CHATGPT` in its provider scope |

Activation is the operator's own decision, not something gated on participant consent -- see
[`../consent-model.md`](../consent-model.md)'s status notice.

Which provider(s) apply to a given chat is visible on that chat's own page in the management UI
(`chat.shared.title` / "What gets shared, and with whom"). Neither provider receives anything
about a chat that has not been activated for it specifically.

## Infrastructure (hosting, does not see message content by design intent, but has physical/administrative access)

| Provider | Role |
|---|---|
| [FILL IN: hosting provider, e.g. Railway] | Runs the application, its PostgreSQL database, and (for the TDLIB adapter) the encrypted local TDLib session directory. Has infrastructure-level access to the running process and its storage; see [`../production-prerequisites.md`](../production-prerequisites.md)'s database role-separation and volume-access sections for what this codebase does and does not control about that access. |
| [FILL IN: any DNS/CDN/proxy provider in front of the application] | Terminates TLS and/or proxies requests; see `tgmcp.forwarded-headers.trusted-hosts` for how this codebase is configured to trust it. |

## Telegram itself

Telegram is the **source** of the data this Service processes, not a subprocessor of the
operator's own processing — but every message this Service reads has already passed through
Telegram's own platform and is subject to Telegram's own terms (see
[`../production-prerequisites.md`](../production-prerequisites.md) section 1 for the unresolved
questions about what Telegram's terms actually permit here).

## What is explicitly NOT a subprocessor

There is no Redis cache, vector store, or third-party analytics/telemetry service integrated in
this codebase as shipped (see [`../retention-policy.md`](../retention-policy.md)). If an operator
adds one, it must be added to this list before going live, along with the retention-story update
that document says any such addition requires.

## Maintaining this list

[FILL IN: process for keeping this list current as the operator's actual infrastructure changes —
e.g., reviewed on every infrastructure change, not just annually.]
