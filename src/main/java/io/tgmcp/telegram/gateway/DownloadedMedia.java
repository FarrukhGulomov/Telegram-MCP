package io.tgmcp.telegram.gateway;

/**
 * The bytes of a message's visual preview (a photo, or a video/video-note/animation's
 * representative thumbnail frame -- see {@link RemoteMessage#mediaKind}), fetched on demand.
 *
 * <p>Never persisted: {@link TelegramGateway#downloadMessageMedia} is called fresh for every
 * request rather than caching this anywhere, so revoking a chat's activation or a participant's
 * consent takes effect on the very next fetch, with nothing left over to separately purge.
 */
public record DownloadedMedia(byte[] bytes, String mimeType) {
}
