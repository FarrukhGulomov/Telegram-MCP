-- Password-based sign-in is removed entirely: every account now signs in with a one-time code
-- delivered to its linked Telegram chat (see LoginTwoFactorService / TelegramCodeLoginController
-- merged into LoginController). No code path reads or writes password_hash any more.
ALTER TABLE app_users DROP COLUMN password_hash;
