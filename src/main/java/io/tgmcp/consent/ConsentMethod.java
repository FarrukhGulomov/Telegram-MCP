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
    /** Synthetic consent belonging to synthetic demo people. Never valid for real data. */
    DEMO_SYNTHETIC
}
