package com.softropic.skillars.platform.security.contract;

/**
 * S-27: lifecycle of one Stripe Identity VerificationSession a self-registered player started.
 * {@link #VERIFIED} and {@link #UNDERAGE} are terminal and are never overwritten by a late or replayed event.
 */
public enum AgeVerificationStatus {
    /** Session created, the player has not submitted anything yet. */
    CREATED,
    /** Stripe is checking the submitted document. */
    PROCESSING,
    /** Stripe could not verify the document; the player may start a fresh session (subject to the daily cap). */
    REQUIRES_INPUT,
    /** Document verified and the verified date of birth is 18+. */
    VERIFIED,
    /** Document verified but the verified date of birth is under 18: the account is blocked. */
    UNDERAGE,
    /** Session cancelled (by the player, or by us after a failed attempt). */
    CANCELED;

    public boolean isTerminal() {
        return this == VERIFIED || this == UNDERAGE;
    }
}
