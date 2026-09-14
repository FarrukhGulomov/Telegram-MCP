# Consent model

## The problem this model exists to solve

A Telegram group chat is a conversation between several people. The account owner who
connects this service can technically read all of it, and a group admin can technically
authorize anything about the group. Neither of those facts makes it acceptable to feed the
conversation to an AI provider. The other participants wrote those messages, they are
identifiable in them, and they did not agree to anything.

So this service treats **the account owner's authority and the participants' consent as two
different things**, and requires both.

## Three separate authorizations

| Authorization | What it permits | Where it lives |
|---|---|---|
| **Connect an account** | Establishing a Telegram client session on behalf of one user | `telegram_connections` |
| **Activate a chat** | Processing one chat's content, for one stated purpose, for a stated provider scope, over a stated date range | `chat_activations` |
| **Participant consent** | One named person agreeing to that exact context | `consent_records` |

Connecting an account grants a session that can see everything that account can see. That is
much broader than "read-only access to one team chat", and the UI says so. Discovery — the
list of chats the connected account can see — is explicitly **not** authorization: a
discovered chat is merely selectable. Nothing is synchronised and no message is stored until
the chat reaches `ACTIVE`.

## Activation lifecycle

```
INACTIVE ──select──▶ AWAITING_CONSENT ──all participants consented──▶ ACTIVE
                            ▲                                          │
                            │                                   pause / consent gap /
              context changed (purpose, providers, range)        membership change
                            │                                          ▼
                            └────────────────────────────────────── PAUSED
                                                                       │
                                                                    revoke
                                                                       ▼
                                                                   REVOKED
```

Only `ACTIVE` is readable. `AWAITING_CONSENT`, `INACTIVE` and `REVOKED` retain no message
content at all — the sync worker purges anything it finds in those states, so a chat that is
merely *proposed* never accumulates a stored copy.

## What a consent record binds

Each record names, and is only valid for, exactly:

- the **participant** (Telegram user id and display name at the time),
- the **chat**,
- the **purpose**, verbatim,
- the **provider scope** (`ANTHROPIC_CLAUDE`, `OPENAI_CHATGPT`, or both),
- the **consent version** (a version string for the wording people agreed to),
- **timestamps**: granted, optional expiry, optional revocation,
- **how it was obtained** (`method`) and, for anything not self-service, an **evidence
  reference**.

Change any element of that context and the record stops covering it. Editing a chat's
purpose, provider scope or approved range in the UI therefore drops it back to
`AWAITING_CONSENT` and deletes the stored messages — the consent that existed described
something else.

## An administrator cannot silently consent for someone else

Consent can be recorded three ways, and the difference is visible in every record:

- `SELF_SERVICE_LINK` — the participant acted themselves.
- `SIGNED_ATTESTATION` — an operator recorded a consent given out of band. This **requires**
  an evidence reference; the service refuses the record without one. It still names the
  participant and the person who entered it, so it is an auditable claim about someone
  else's agreement, not a substitute for it.
- `DEMO_SYNTHETIC` — synthetic consent belonging to synthetic demo people. Never valid for
  real data.

Nothing in the model lets one person's action produce blanket coverage for a group.

## Coverage is recomputed, never cached

Before any content is returned, the service recomputes coverage: for every current, non-bot
participant of the chat, is there a live consent record matching the current purpose,
provider scope and consent version? Bots are excluded — they are not people.

Coverage is checked **twice per MCP request**: once before querying, and again after the rows
have been read but before the response is serialised. That second check is what makes a
revocation landing mid-request still deny the response.

Access pauses automatically when:

- any required participant's consent is missing, expired or revoked;
- the participant list is unknown, so coverage cannot be established at all;
- the **membership changes** — a join or leave changes who must consent, so the chat pauses
  for renewed review rather than continuing under the old set's agreement;
- the **purpose or provider scope changes**.

## What consent does and does not achieve

Recorded consent is a necessary condition this service enforces. It is **not** a claim that
processing is lawful, that Telegram's terms permit it, or that any particular regulation is
satisfied. Consent recorded here:

- does not bind participants who are not in the chat but are discussed in it;
- does not cover forwarded content authored by third parties;
- does not survive a change in purpose;
- cannot be withdrawn retroactively from an AI provider that has already received the text.

The last point is the one to be loudest about. When an MCP client retrieves a message, the
text is sent to that client's provider and processed under **that provider's** terms.
Revoking here stops future disclosure and deletes the local copy. It does not, and cannot,
recall what was already disclosed. The management UI states this on every chat page.

See [production-prerequisites.md](production-prerequisites.md) for what remains unresolved
before any of this touches a real account.
