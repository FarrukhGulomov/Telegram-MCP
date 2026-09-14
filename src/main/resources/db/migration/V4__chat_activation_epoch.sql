-- Security-audit finding F07 (P0): a chat's activation could be revoked while a sync run
-- already in flight (fetched from Telegram, not yet stored) was mid-way through committing
-- messages, so the revoke had no way to stop content that was already "in the pipe" from
-- landing after access had been withdrawn. A plain re-check of activation.state before each
-- store() would mostly close this, but not the ABA case where the same chat is revoked and
-- then re-proposed/re-activated (reusing the same activation row) inside that same window --
-- state would read ACTIVE again by the time the in-flight run rechecks it. epoch is a
-- monotonically increasing fencing token bumped on every revoke, so an in-flight run captured
-- against an older epoch is detected and aborted even if the state happens to match again.
ALTER TABLE chat_activations ADD COLUMN epoch bigint NOT NULL DEFAULT 0;
