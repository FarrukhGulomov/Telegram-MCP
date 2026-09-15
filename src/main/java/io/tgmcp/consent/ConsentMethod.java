package io.tgmcp.consent;

public enum ConsentMethod {
    /** The participant followed a per-person link and consented themselves. */
    SELF_SERVICE_LINK,
    /**
     * An operator recorded a consent the participant gave out of band, and must supply an
     * evidence reference. This is still not an administrator consenting *for* someone —
     * the record names the participant and the evidence that they agreed.
     *
     * <p>Security-audit finding SEC-08: the evidence reference should name something an
     * auditor could independently go and check later — a link to a signed document, an email
     * or chat message id, a support-ticket number, or a dated, specific description of the
     * interaction (who, when, how) — not a bare assertion like "confirmed" or "yes" that adds
     * nothing beyond the operator's own say-so. {@link io.tgmcp.consent.ConsentService} enforces
     * only a minimum length as a low bar against the most obvious placeholder text; it cannot
     * verify that a reference actually points to anything, so the standard above is a
     * documentation expectation for whoever records it, not something this application checks.
     */
    SIGNED_ATTESTATION,
    /** Synthetic consent belonging to synthetic demo people. Never valid for real data. */
    DEMO_SYNTHETIC
}
