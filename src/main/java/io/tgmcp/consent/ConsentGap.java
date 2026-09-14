package io.tgmcp.consent;

/** Why one participant is not covered. Used to explain a pause in the UI. */
public record ConsentGap(long participantTgUserId, String displayName, String reason) {

    public static final String MISSING = "No consent record for the current purpose, providers and consent version";
    public static final String REVOKED = "Consent revoked";
    public static final String EXPIRED = "Consent expired";
    public static final String NARROWER_SCOPE = "Consent does not cover the requested provider scope";
}
