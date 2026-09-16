package io.tgmcp.consent;

import java.util.List;

/**
 * Whether every person whose consent is required currently has valid consent.
 *
 * <p>Coverage is recomputed rather than cached: it is checked before activation, on every
 * sync pass, and again immediately before any content is returned to an MCP client.
 */
public record ConsentCoverage(int requiredParticipants, int coveredParticipants, List<ConsentGap> gaps) {

    /**
     * {@code noParticipantsKnown()} always carries an explicit gap, so a chat whose membership
     * genuinely cannot be determined never satisfies this; the only way to reach zero required
     * participants with an empty gap list is the deliberate {@link #channelBroadcast()} state.
     */
    public boolean complete() {
        return this.gaps.isEmpty() && this.coveredParticipants == this.requiredParticipants;
    }

    public static ConsentCoverage noParticipantsKnown() {
        return new ConsentCoverage(0, 0,
                List.of(new ConsentGap(0L, "(unknown)", "Participant list is unknown, so consent coverage is uncertain")));
    }

    /**
     * A broadcast channel has no participants whose personal correspondence is being disclosed
     * -- its content is authored by the channel itself (or its admins acting for it) for a
     * public audience, the same way a blog post needs no consent from its readers. Per-
     * participant consent coverage does not apply to it at all, which is a different, always-
     * satisfied state from {@link #noParticipantsKnown()} (a chat whose membership genuinely
     * cannot be determined, which must stay blocked rather than default to "fine").
     */
    public static ConsentCoverage channelBroadcast() {
        return new ConsentCoverage(0, 0, List.of());
    }
}
