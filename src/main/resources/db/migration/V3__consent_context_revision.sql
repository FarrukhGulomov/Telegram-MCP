-- Security audit finding F03 (P0): consent was only ever matched against purpose +
-- provider scope + consent version. approvedFrom/approvedTo/retentionDays were never part of
-- what a consent record covered, so widening a chat's approved date range or retention (or
-- reverting to an earlier purpose that happens to have old, still-unexpired consent on file)
-- could let a chat re-activate without anyone actually re-approving the new context.
--
-- context_revision makes the full context (purpose, provider scope, consent version, approved
-- range, retention) one immutable, opaque identity: any change to any part of it is a new
-- revision, and a consent record only ever covers the exact revision it was granted under.
--
-- Existing activations each get a fresh, distinct revision on migration rather than one
-- computed from their current fields, and existing consent records are left with no revision
-- at all (NULL, which can never match any revision going forward) -- so anything already
-- granted before this migration cannot silently carry over as covering the "new" identity
-- scheme; every chat starts needing fresh consent under it, which is the safe direction to err
-- in for a consent-enforcement fix.

ALTER TABLE chat_activations
    ADD COLUMN context_revision varchar(36) NOT NULL DEFAULT gen_random_uuid()::text;
ALTER TABLE chat_activations
    ALTER COLUMN context_revision DROP DEFAULT;

ALTER TABLE consent_records
    ADD COLUMN context_revision varchar(36);

CREATE INDEX consent_records_context_revision_idx
    ON consent_records (tenant_id, chat_id, participant_tg_user_id, context_revision);
