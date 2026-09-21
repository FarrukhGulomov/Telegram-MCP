-- Adds an on-demand visual-preview reference to stored messages: a photo, or a representative
-- thumbnail frame for a video/video-note/animation (never the full video -- see
-- RemoteMessage#mediaKind's javadoc). Neither column ever holds the media's actual bytes: the
-- application fetches those fresh from Telegram on every request (TelegramGateway
-- #downloadMessageMedia) rather than storing or caching them, so revoking a chat's activation
-- or a participant's consent takes effect on the very next fetch with nothing left over to
-- separately purge.
ALTER TABLE messages
    ADD COLUMN media_kind varchar(24),
    ADD COLUMN media_remote_file_id varchar(200);
