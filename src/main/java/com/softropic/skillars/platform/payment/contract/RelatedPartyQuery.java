package com.softropic.skillars.platform.payment.contract;

import java.util.UUID;

/**
 * feat/security-s06-related-party: the read side of related-party detection, for modules that must not depend on its
 * service (reviews). A user is a related-party author of a coach once any booking between them was flagged
 * ({@code payment.related_party_bookings}).
 */
public interface RelatedPartyQuery {

    /**
     * Whether a review by this user (the payer, or the user of the player the payer booked for) of this coach must be
     * stored but neither shown nor counted.
     */
    boolean isRelatedPartyAuthor(UUID coachId, Long authorUserId);
}
