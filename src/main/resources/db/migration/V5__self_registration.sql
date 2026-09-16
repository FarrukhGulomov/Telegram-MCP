-- Users change frequently and should not require an operator to create their account by hand.
-- Two new self-service sign-in paths need a place to record their state:
--   * email/password self-registration (no schema change -- app_users already has everything)
--   * "Sign in with Telegram": app_users gains the Telegram user id it was provisioned from or
--     linked to, so a returning Telegram identity resolves back to the same account. A Postgres
--     UNIQUE constraint treats every NULL as distinct, so password-only accounts (NULL here) are
--     unaffected.
ALTER TABLE app_users ADD COLUMN telegram_user_id bigint UNIQUE;
