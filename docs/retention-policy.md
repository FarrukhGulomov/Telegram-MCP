# Retention policy

## What is stored

| Data | Where | Lifetime |
|---|---|---|
| Message text, sender, timestamps | `messages` | The narrower of the approved date range and the per-chat retention period |
| Search index | `messages.content_tsv`, a generated column | Identical to the row — it is the same row |
| Chat and participant metadata | `chats`, `chat_participants` | Until the chat is deleted or the connection is removed |
| Consent records | `consent_records` | Kept after revocation, as evidence that consent existed and was withdrawn |
| Sync checkpoints | `sync_checkpoints` | Reset on revocation or context change |
| Audit events | `audit_events` | Kept; contains no message text |
| Telegram session | `telegram_connections.session_ciphertext` | Until disconnect |

There is **no** Redis cache, no vector store and no separate search index in this MVP. That
is a deliberate retention decision as much as a scope decision: one row is the only copy, so
deleting it removes the message from storage, from search and from every derived read path in
a single statement. Adding a cache or an embedding store later means adding a second deletion
path, and the retention story has to be re-established at that point.

## When data is deleted

- **Retention sweep** (hourly by default): deletes messages outside the approved range or
  older than the chat's retention period. Idempotent.
- **Context change**: editing purpose, provider scope or approved range returns the chat to
  `AWAITING_CONSENT` and deletes its stored messages, because the consent that existed
  described a different context.
- **Revocation**: blocks reads immediately, then deletes stored messages and resets the sync
  checkpoint, in the same transaction.
- **Delete data**: an explicit operator action that removes stored messages without changing
  the activation state.
- **Upstream deletion**: when Telegram reports a message deleted, its text is dropped
  immediately and the row becomes an empty tombstone. The tombstone keeps ingestion
  idempotent and is never resurrected — a later sync pass cannot repopulate its text. The
  message stops appearing in reads and in search at once.
- **Not-yet-active chats**: any content found for a chat in `INACTIVE`, `AWAITING_CONSENT` or
  `REVOKED` is purged by the sync worker. Content is not retained while a chat awaits
  activation.

## Backups and residual data — the honest part

Deleting a row deletes it from the live database. It does **not**:

- remove it from any PostgreSQL backup, snapshot, WAL archive or replica taken before the
  deletion;
- remove it from your host's filesystem until PostgreSQL reuses the pages (`DELETE` marks
  tuples dead; `VACUUM` reclaims the space, and neither is a secure erase);
- remove it from an AI provider that already received it through an MCP tool call;
- remove it from anything a person copied out of a model's answer.

If you run this for real you need, at minimum:

1. **A backup retention period you can state**, shorter than any deletion promise you make,
   with restores that are re-scrubbed or that you accept as a re-disclosure.
2. **Encrypted backups** whose keys can be destroyed, so key destruction bounds the residual
   lifetime.
3. **A documented maximum residual window** — "deleted within 24 hours from live systems, and
   within N days from backups" — communicated to participants before consent, not after.
4. **A `VACUUM` policy** if the space-reclamation timing matters to you.

None of this is implemented here, and none of it can be implemented purely in application
code.

## What the service tells clients

Every MCP response carries a `coverage` block: the approved range, the retention period, the
effective window after both are applied, what is actually stored, and whether backfill
finished. When backfill is incomplete the note says so explicitly and warns against treating
an absent message as evidence it was never sent. The service never advertises complete
historical coverage it does not have.
