package io.tgmcp.telegram.gateway;

public record RemoteParticipant(long tgUserId, String displayName, boolean bot) {
}
