-- Second factor for signing in to the management UI: after a password check (or a Telegram
-- "Sign in with Telegram" widget confirmation), a short numeric code sent to the account's
-- linked Telegram chat must also be entered before a session is established. Only the hash is
-- stored -- like password_hash, a database dump alone must never hand out a working code.
--
-- seq is a plain insertion-order tiebreaker, separate from the id primary key: two codes for
-- the same user can otherwise share an identical created_at (client clocks used in testing are
-- sometimes frozen, and even a real clock has finite resolution), and "the code just issued" is
-- exactly the row every lookup here needs -- never an arbitrarily-chosen row among a tie.
CREATE TABLE login_verification_codes (
    id uuid PRIMARY KEY,
    seq bigserial NOT NULL,
    app_user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    code_hash text NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    attempts int NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Looked up by app_user_id on every verify/resend; old rows for the same user are superseded
-- (never deleted, for audit) each time a fresh code is issued.
CREATE INDEX idx_login_verification_codes_user ON login_verification_codes (app_user_id, seq DESC);
