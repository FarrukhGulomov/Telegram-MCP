# Privacy policy — DRAFT

> **This is a draft, not a published policy, and not legal advice.** It is grounded in what this
> codebase actually does today (cross-referenced below), so it does not invent behavior the
> application does not have. It must be reviewed by counsel, filled in with the operator's real
> identity/contact details, and formally adopted before being shown to any real user or
> participant. Placeholders are marked `[FILL IN]`.
>
> **Corrected 2026-09-23:** this draft originally said content is shared "only once every
> current participant has consented." That has not been true since the operator's 2026-09-20
> decision to remove per-participant consent as a technical precondition for activation (see
> [`../consent-model.md`](../consent-model.md)'s own status notice) — the account owner's own
> decision to activate a chat is now the only gate this application enforces. Fixed below; if
> this policy is adopted before that gate is restored (if it ever is), it must not claim
> participant-level consent gating exists.

## Who this is

**Operator:** [FILL IN: legal entity name, address, jurisdiction]
**Contact:** [FILL IN: see also `support-and-security-contact.md`]

## What this service does, in plain terms

This service connects one person's Telegram account, lets that person select specific group
chats, and — once that person (the account owner) activates a chat — makes that chat's messages
available to an AI assistant (currently Claude and/or ChatGPT, chosen per chat) through a
tool-calling protocol (MCP). **The application does not itself require or verify that other
participants in the chat have consented before activation** — that gate was removed by the
operator's own explicit decision on 2026-09-20; recording a participant's consent is a tool the
operator can still choose to use (`../consent-model.md`), and doing so, or not, is the operator's
own legal and ethical responsibility, not a control this application enforces on their behalf.
[FILL IN: describe here, honestly, whatever consent process the operator actually follows outside
this application, if any — this section must not claim more than that.]

## What is collected and why

| Data | Why | Detail |
|---|---|---|
| The connected account's Telegram session | To read the chats that account can see | [`../consent-model.md`](../consent-model.md) |
| Message text, sender, and timestamps, for **activated chats only** | The stated purpose of that chat's activation | [`../retention-policy.md`](../retention-policy.md) |
| Chat and participant metadata | To track chat membership (no longer used to gate activation on consent -- see the status notice above) | [`../retention-policy.md`](../retention-policy.md) |
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

- **Participants** whose consent the operator chose to record can have it withdrawn at any time
  through the operator's `/consent/revoke` action; in this application's current implementation
  that action also pauses the entire chat (stopping all disclosure from it, not narrowly just
  that participant's messages) and deletes that participant's own stored messages
  (`../consent-model.md`). There is no participant-facing self-service withdrawal built into this
  application — a participant must reach the operator directly. [FILL IN: the actual channel
  through which a participant can request this.]
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
