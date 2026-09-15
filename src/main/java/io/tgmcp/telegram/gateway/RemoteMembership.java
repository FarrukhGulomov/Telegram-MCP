package io.tgmcp.telegram.gateway;

import java.util.List;

/**
 * The result of one membership enumeration attempt, together with whether it can be trusted
 * as the whole membership.
 *
 * <p>Security-audit finding SEC-02: a plain {@code List<RemoteParticipant>} could not
 * distinguish "this is genuinely everyone" from "this is as far as we were willing to go" (a
 * safety cap on how many members one call will page through) or "this is as far as the source
 * would let us verify." Callers that treat a capped or otherwise unverifiable result as if it
 * were complete understate who is actually in the chat -- consent can look satisfied for the
 * subset returned while other, unseen members remain unconsulted -- and a departure check
 * against that same partial list wrongly reports real, still-present members as having left.
 */
public record RemoteMembership(List<RemoteParticipant> participants, boolean complete) {

    public RemoteMembership {
        participants = List.copyOf(participants);
    }

    public static RemoteMembership complete(List<RemoteParticipant> participants) {
        return new RemoteMembership(participants, true);
    }

    public static RemoteMembership incomplete(List<RemoteParticipant> participants) {
        return new RemoteMembership(participants, false);
    }
}
