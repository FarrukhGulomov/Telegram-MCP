-- Replaces the oauth.telegram.org "Sign in with Telegram" widget (a bot-identity confirmation
-- click) with a plain Bot API deep link: a visitor opens the bot and sends /start <token>,
-- our webhook receives that update and links the token to the sender's Telegram identity.
--
-- telegram_username is the sender's @handle at the moment of linking (Telegram usernames are
-- optional and can change; this is a snapshot, used only so "sign in with a Telegram code" can
-- also be reached by typing a username instead of an email). Unlike telegram_user_id, a numeric
-- id, this cannot be trusted to stay unique or even present forever, so it stays a plain
-- lookup column rather than the identity itself.
ALTER TABLE app_users ADD COLUMN telegram_username text;
CREATE UNIQUE INDEX idx_app_users_telegram_username ON app_users (lower(telegram_username))
    WHERE telegram_username IS NOT NULL;

-- One row per link attempt (registration's "open the bot" step). The token is the whole secret;
-- a webhook update naming an unknown, expired, or already-linked token is simply ignored.
CREATE TABLE telegram_link_tokens (
    token text PRIMARY KEY,
    telegram_user_id bigint,
    telegram_username text,
    linked_at timestamptz,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
