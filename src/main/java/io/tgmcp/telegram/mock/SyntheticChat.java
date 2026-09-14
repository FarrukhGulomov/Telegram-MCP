package io.tgmcp.telegram.mock;

import java.util.ArrayList;
import java.util.List;

import io.tgmcp.telegram.gateway.RemoteChat;
import io.tgmcp.telegram.gateway.RemoteMessage;
import io.tgmcp.telegram.gateway.RemoteParticipant;

/** Mutable in-memory chat used by the mock adapter. */
public class SyntheticChat {

    private final RemoteChat descriptor;
    private final List<RemoteParticipant> participants = new ArrayList<>();
    private final List<RemoteMessage> messages = new ArrayList<>();

    public SyntheticChat(RemoteChat descriptor) {
        this.descriptor = descriptor;
    }

    public RemoteChat descriptor() {
        return this.descriptor;
    }

    public List<RemoteParticipant> participants() {
        return this.participants;
    }

    public List<RemoteMessage> messages() {
        return this.messages;
    }
}
