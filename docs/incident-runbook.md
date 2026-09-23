# Incident runbook

This states what actually exists today, not an aspirational SLA. See
[production-prerequisites.md](production-prerequisites.md) and
[retention-policy.md](retention-policy.md) for the gaps this runbook cannot paper over.

## What this deployment does and does not give you

- **No automated alerting is wired up.** `/actuator/health` and `/actuator/info` exist
  (`DeploymentInfoContributor` reports the configured adapter and whether real-account access is
  authorized), but nothing pages anyone when health flips to `DOWN` or when Railway restarts a
  crashed instance. Until an alerting integration (e.g. a Railway webhook into Slack/PagerDuty/
  Better Stack) is wired up, "monitoring" means someone checking the dashboard.
- **No automated encrypted backup / restore drill exists.** Railway's own Postgres plugin (used
  by `telegram-mcp-tdlib-test`) takes its own snapshots on its own schedule; this codebase does
  not configure, verify, or restore-test them. Treat whatever Railway's plugin does as the
  current backup story until someone has actually run a restore and timed it.
- **RPO/RTO, stated honestly, given the above:** Recovery Point Objective = whatever the
  Postgres host's own snapshot interval is (unverified from this codebase); Recovery Time
  Objective = untimed, because no restore has been rehearsed. Do not quote a tighter number to
  anyone until a real restore has been run and timed.
- **`SingleInstanceGuard`** (added as an audit follow-up) means a second instance started
  against the same database while `tgmcp.telegram.real-account-access-authorized=true` refuses
  to start outright, rather than silently corrupting the rate limiter and TDLib session
  ownership. If a redeploy ever fails at startup with "another instance already holds this
  database's single-instance advisory lock", it means the previous instance has not actually
  shut down yet (or crashed without PostgreSQL noticing the backend disconnect) -- see below.

## Suspected compromised Telegram session (real-account deployment only)

Signals: a chat you never activated becomes readable, a login you didn't start reaches `READY`,
or you observe unexpected `outgoing` activity on the connected Telegram account.

1. **Disconnect the account immediately** from the management UI (`/account` or the connected
   chat's page → the disconnect/revoke action), or set
   `tgmcp.telegram.real-account-access-authorized=false` and redeploy if the UI is not reachable.
   `TelegramConnectionService.disconnect` tears down the TDLib session and clears
   `telegram_connections.session_ciphertext`.
2. **Revoke Telegram's own active sessions for that account** from a Telegram client on another
   device (Settings → Devices → terminate all sessions) -- this application cannot do this for
   you, and disconnecting locally does not by itself invalidate a session Telegram itself still
   considers active.
3. **Rotate `tgmcp.secrets.session-encryption-key`.** Per
   [production-prerequisites.md](production-prerequisites.md)'s key-rotation section, this
   invalidates every existing encrypted session/TDLib database -- treat it as forcing a fresh,
   re-authenticated login into an empty TDLib directory, not an in-place rekey.
4. **Check `audit_events`** for the affected tenant (`SELECT * FROM audit_events WHERE tenant_id
   = ? ORDER BY occurred_at DESC`) -- the table is tamper-evident (append-only; see the `V11`
   migration), so this is a trustworthy record of what actually happened, not something that
   could have been quietly edited after the fact.
5. **Notify affected participants** if any consented chat's content may have been exposed beyond
   its approved scope. This is an operational/legal obligation this codebase cannot automate --
   see [production-prerequisites.md](production-prerequisites.md) section 2.

## Suspected database compromise

1. Rotate the database credential (`TGMCP_DB_PASSWORD`) immediately at the hosting provider.
2. Rotate `tgmcp.secrets.session-encryption-key`, `tgmcp.secrets.cursor-signing-key`, and
   `tgmcp.secrets.oauth-rsa-private-key` -- assume anything at rest in that database, including
   encrypted session material, is no longer to be trusted.
3. Every currently-issued OAuth access token becomes invalid once the signing key rotates
   without the old key added to `tgmcp.secrets.retiring-oauth-verification-keys` -- do not add
   it in this case; the point is to invalidate outstanding tokens, not preserve them.
4. Audit `audit_events` for the actual access pattern before the compromise was noticed.

## A redeploy fails to start with "another instance already holds this database's
## single-instance advisory lock"

This is `SingleInstanceGuard` doing its job, not a bug to route around:

- If you deliberately scaled replicas above 1: scale back to exactly 1. This application's rate
  limiter and TDLib session ownership are in-memory, single-instance assumptions (see
  [production-prerequisites.md](production-prerequisites.md)) that a second replica would
  silently violate.
- If the previous instance crashed and Railway has not yet recycled its connection: the lock
  releases automatically once PostgreSQL notices that backend has actually disconnected
  (typically within the host's TCP keepalive window) -- wait and retry rather than restarting
  repeatedly in a loop.
- Never work around this by connecting a second database, or by disabling the guard, without
  first understanding why a second instance is starting against the same database at all.

## Escalation

There is no on-call rotation, paging integration, or support SLA configured for this deployment.
Until one exists, whoever holds the Railway project's credentials is the entire incident
response team. Add real contacts here once they exist -- do not leave this section pointing at
nobody once this ever handles a real account.
