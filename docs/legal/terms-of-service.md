# Terms of service — DRAFT

> **This is a draft, not a published agreement, and not legal advice.** It must be reviewed by
> counsel and completed with the operator's real details before being shown to any real user.
> Placeholders are marked `[FILL IN]`.
>
> **Corrected 2026-09-23:** this draft originally said the Service enforces per-participant
> consent before exposing a chat. It does not, and has not since the operator's 2026-09-20
> decision to remove that gate (see [`../consent-model.md`](../consent-model.md)'s status
> notice). Section 1 and section 4 below are corrected; do not let this document imply the
> Service verifies participant consent on the operator's behalf.

## 1. What you are agreeing to

These terms govern use of [FILL IN: product name] (the "Service"), operated by
[FILL IN: legal entity name] ("we", "us"). The Service connects a Telegram account and lets its
owner selectively expose specific group chats to an AI assistant, once the owner activates that
chat. **The Service does not verify or require that other chat participants have consented** --
it offers an optional consent-recording tool the owner may use, and enforcing that any actual
consent exists is the owner's own responsibility, not the Service's. See
[`../consent-model.md`](../consent-model.md) for exactly how the optional tool works.

## 2. Who can use this

[FILL IN: minimum age, whether this is offered to individuals, organizations, or both, and any
jurisdictional restrictions counsel identifies from
[`../production-prerequisites.md`](../production-prerequisites.md) section 1's unresolved
Telegram-terms questions.]

## 3. Your responsibilities as the account owner

- You represent that connecting your Telegram account and activating a chat is something you are
  authorized to do — including, for a work or organizational chat, whatever internal permission
  your organization requires.
- You are responsible for accurately describing a chat's **purpose** when activating it: consent
  recorded here binds participants to that purpose and nothing else
  (`../consent-model.md`).
- You must not use self-attested consent (`SIGNED_ATTESTATION`) to record agreement a participant
  did not actually give. The Service requires an evidence reference for this method specifically
  so it is auditable; misusing it is a violation of these terms and, potentially, of law.
- You are responsible for participant notification and any consent/disclosure obligation that
  exists independently of this Service's own controls — see
  [`../production-prerequisites.md`](../production-prerequisites.md) section 2.

## 4. What the Service does and does not promise

- The Service enforces the scope and retention rules described in
  [`../retention-policy.md`](../retention-policy.md) (approved date range, retention period,
  deletion on revoke/pause/context-change). It does **not** enforce per-participant consent as a
  precondition -- see the correction notice at the top of this document and
  [`../consent-model.md`](../consent-model.md).
- The Service does **not** control what an AI provider does once it receives a message through a
  tool call — see [`subprocessors.md`](subprocessors.md) and each provider's own terms.
- The Service currently gives no formal uptime, support-response, or data-recovery commitment.
  See [`../incident-runbook.md`](../incident-runbook.md) for the honest current state of backups
  and recovery, and do not represent a stronger guarantee to your own users than that document
  supports.
- [FILL IN: any SLA the operator does choose to commit to, once the gaps above are actually
  closed — not before.]

## 5. Suspension and termination

[FILL IN: grounds for suspending an account — e.g., abuse of the consent mechanism, exceeding
rate limits deliberately, violating Telegram's own terms through this Service — and what happens
to stored data on termination, cross-referencing the deletion behavior in
[`../retention-policy.md`](../retention-policy.md).]

## 6. Liability

[FILL IN: counsel-drafted limitation-of-liability and indemnification language, informed by the
actual, stated unknowns in [`../production-prerequisites.md`](../production-prerequisites.md) —
an MVP with unresolved Telegram-terms questions and unverified edit/delete sync should not be
represented under the same liability terms as a mature, fully-verified product.]

## 7. Governing law and disputes

[FILL IN.]

## 8. Changes to these terms

[FILL IN: notice mechanism and effective-date policy.]
