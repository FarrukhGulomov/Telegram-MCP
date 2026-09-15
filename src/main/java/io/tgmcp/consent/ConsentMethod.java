package io.tgmcp.consent;

public enum ConsentMethod {
    /** The participant followed a per-person link and consented themselves. */
    SELF_SERVICE_LINK,
    /**
     * An operator recorded a consent the participant gave out of band, and must supply an
     * evidence reference. This is still not an administrator consenting *for* someone —
     * the record names the participant and the evidence that they agreed.
     */
    SIGNED_ATTESTATION,
    /**
     * An operator recorded a consent the participant gave out of band, with no evidence
     * reference at all — a deliberate, informed simplification for a single-operator
     * deployment where a one-line evidence field was found to be too much friction for the
     * management UI's main flow. This is weaker than {@link #SIGNED_ATTESTATION} (there is
     * nothing to check the claim against later) but is not {@link #SELF_SERVICE_LINK}: the
     * record is still the operator's own click, at a known time, under their own account —
     * exactly what the audit log captures — never a claim that the participant proved
     * anything themselves.
     */
    OPERATOR_CONFIRMED,
    /** Synthetic consent belonging to synthetic demo people. Never valid for real data. */
    DEMO_SYNTHETIC
}
