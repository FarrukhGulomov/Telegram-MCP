-- Auth is no longer email/bot-code based at all: an account signs in (and, on first use,
-- is created) by completing the exact same phone-number/QR + Telegram-delivered code (+
-- two-factor password, if set) login TDLib itself performs -- there is no separate identity
-- string to type. email and the bot-linking machinery it powered are removed entirely.
ALTER TABLE app_users DROP CONSTRAINT app_users_email_unique;
ALTER TABLE app_users DROP COLUMN email;

-- The account's own phone number, confirmed by Telegram's own login flow (never merely typed
-- and trusted): nullable because a pre-existing account created before this migration has
-- none yet, and a Postgres UNIQUE constraint treats every NULL as distinct.
ALTER TABLE app_users ADD COLUMN phone_number varchar(32) UNIQUE;

DROP TABLE login_verification_codes;
DROP TABLE telegram_link_tokens;

-- The real phone number Telegram's own login just confirmed (never merely typed into the
-- form), stored alongside the existing tg_user_id/tg_username -- see PreAuthLoginService,
-- which uses it to overwrite the account's own phone_number once a login actually succeeds.
ALTER TABLE telegram_connections ADD COLUMN tg_phone_number varchar(32);
