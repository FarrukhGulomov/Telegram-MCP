# Privacy policy — DRAFT

> **This is a draft, not a published policy, and not legal advice.** It is grounded in what this
> codebase actually does today (cross-referenced below), so it does not invent behavior the
> application does not have. It must be reviewed by counsel, filled in with the operator's real
> identity/contact details, and formally adopted before being shown to any real user or
> participant. Placeholders are marked `[FILL IN]`.

## Who this is

**Operator:** [FILL IN: legal entity name, address, jurisdiction]
**Contact:** [FILL IN: see also `support-and-security-contact.md`]

## What this service does, in plain terms

This service connects one person's Telegram account, lets that person select specific group
chats, and — only once every current participant in a chat has consented — makes that chat's
messages available to an AI assistant (currently Claude and/or ChatGPT, chosen per chat) through
a tool-calling protocol (MCP). The full mechanics are documented in
[`../consent-model.md`](../consent-model.md); this policy describes the same behavior for a
non-technical reader and states the operator's commitments around it.

## What is collected and why

| Data | Why | Detail |
|---|---|---|
| The connected account's Telegram session | To read the chats that account can see | [`../consent-model.md`](../consent-model.md) |
| Message text, sender, and timestamps, for **activated chats only** | The stated purpose of that chat's activation | [`../retention-policy.md`](../retention-policy.md) |
| Chat and participant metadata | To determine who must consent | [`../retention-policy.md`](../retention-policy.md) |
| Consent records (who agreed, to what, when) | To prove and enforce consent | [`../consent-model.md`](../consent-model.md) |
| Audit events (actions taken, not message content) | Accountability and incident investigation | `audit_events` is append-only at the database level (tamper-evident; see the `V11` migration) |

Nothing is collected from a chat that has not been explicitly activated, and nothing is stored
for a chat that is not yet `ACTIVE` — an inactive or awaiting-consent chat's content is purged by
the sync worker, not merely left unread.

## Who this reaches beyond the operator

When an AI assistant tool call retrieves a message, that message's text is sent to **that AI
assistant's provider** (Anthropic for Claude, OpenAI for ChatGPT) and processed under **that
provider's own terms and privacy practices**, not this document. See
[`subprocessors.md`](subprocessors.md) for the current list. This is stated explicitly on every
activated chat's page in the management UI, not only here.

**This cannot be undone after the fact.** Revoking consent or access here stops *future*
disclosure and deletes this application's own stored copy. It does not recall a message a
provider has already received, and it does not undo anything a person copied out of that
provider's response. See [`../consent-model.md`](../consent-model.md)'s closing section.

## How long data is kept

Governed entirely by [`../retention-policy.md`](../retention-policy.md): the shorter of the
approved date range and the chat's own configured retention period, deleted automatically on a
sweep, and deleted immediately (not merely marked) on revocation, a context change, or an
explicit delete-data action. That document also states plainly what deletion does **not** reach
(backups, WAL archives, anything already sent to a provider) — read that section before making
any retention promise to a participant.

## Your rights

- **Participants** (people in an activated chat) can withdraw consent at any time through
  whatever consent mechanism the operator used to record it; withdrawal stops future disclosure
  and deletes stored content for that participant (`../consent-model.md`).
- **The account owner** who connected Telegram can pause, revoke, or delete data for any chat
  from the management UI at any time.
- [FILL IN: jurisdiction-specific rights — access, correction, erasure, objection, portability,
  and how to exercise them — once counsel has confirmed which apply.]

## What this policy does not establish

Recorded consent, as this application implements it, is a technical control, not a legal
conclusion. It does not by itself establish a lawful basis for processing in every jurisdiction,
does not resolve controller/processor roles between the operator, this service, and the AI
provider, and does not address people who are discussed in a chat but never joined it. See
[`../production-prerequisites.md`](../production-prerequisites.md) section 2 for the specific,
unresolved legal questions this draft does not answer.

## Changes to this policy

[FILL IN: how participants and account owners will be notified of material changes, and whether
continued use constitutes acceptance.]
