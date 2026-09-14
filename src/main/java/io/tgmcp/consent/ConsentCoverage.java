package io.tgmcp.consent;

import java.util.List;

/**
 * Whether every person whose consent is required currently has valid consent.
 *
 * <p>Coverage is recomputed rather than cached: it is checked before activation, on every
 * sync pass, and again immediately before any content is returned to an MCP client.
 */
public record ConsentCoverage(int requiredParticipants, int coveredParticipants, List<ConsentGap> gaps) {

    public boolean complete() {
        return this.gaps.isEmpty() && this.requiredParticipants > 0
                && this.coveredParticipants == this.requiredParticipants;
    }

    public static ConsentCoverage noParticipantsKnown() {
        return new ConsentCoverage(0, 0,
                List.of(new ConsentGap(0L, "(unknown)", "Participant list is unknown, so consent coverage is uncertain")));
    }
}
