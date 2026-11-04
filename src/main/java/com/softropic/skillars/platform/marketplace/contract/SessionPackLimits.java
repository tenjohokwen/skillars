package com.softropic.skillars.platform.marketplace.contract;

/**
 * skillars-deferred-168: the bounds a coach's session pack must meet, shared by the profile builder's
 * step 3 ({@link ProfileBuilderStep3Request}) and the payment module's tier page
 * ({@code CreateSessionPackTierRequest}). They live here, in marketplace, because payment already depends
 * on marketplace and marketplace must not depend on payment; both requests reference these constants so
 * the two paths that write the same offer can never drift apart.
 */
public final class SessionPackLimits {

    /** Largest pack a coach may sell. */
    public static final int MAX_SESSION_COUNT = 100;

    /** Highest total price, inside {@code session_pack_tiers.total_price numeric(10,2)}. */
    public static final String MAX_TOTAL_PRICE = "50000.00";

    /** Longest pack label, the width of {@code session_pack_tiers.label}. */
    public static final int MAX_LABEL_LENGTH = 200;

    private SessionPackLimits() {
    }
}
