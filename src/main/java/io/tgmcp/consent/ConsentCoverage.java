package io.tgmcp.consent;

import java.util.List;

/**
 * Reports how many of a chat's participants have consent on record.
 *
 * <p>Per this deployment's explicit operator decision (see {@link
 * ConsentService#coverageFor}), nothing in this application gates on {@link #complete()} any
 * more -- it is kept only to drive the participants table and audit trail in the management
 * UI, not to block activation or reading.
 */
public record ConsentCoverage(int requiredParticipants, int coveredParticipants, List<ConsentGap> gaps) {

    public boolean complete() {
        return this.gaps.isEmpty() && this.coveredParticipants == this.requiredParticipants;
    }
}
