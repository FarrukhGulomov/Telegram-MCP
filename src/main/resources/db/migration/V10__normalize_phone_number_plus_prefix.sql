-- TDLib's own getMe().phone_number is bare digits with no "+" (see
-- TdlibTelegramGateway.withAccountIdentity, now fixed to add one before it ever reaches
-- PreAuthLoginService.finishIfReady). Before that fix, a real login's confirmed identity
-- overwrote app_users.phone_number without a "+", so a returning visitor's own, "+"-prefixed
-- normalizePhoneNumber() lookup could no longer find their own account and instead collided
-- with it trying to create a second one (tenants.slug is derived from the same digits). Backfill
-- every row still missing the "+" so it matches the canonical form the app now always produces.
UPDATE app_users SET phone_number = '+' || phone_number
WHERE phone_number IS NOT NULL AND phone_number NOT LIKE '+%';

UPDATE telegram_connections SET tg_phone_number = '+' || tg_phone_number
WHERE tg_phone_number IS NOT NULL AND tg_phone_number NOT LIKE '+%';
