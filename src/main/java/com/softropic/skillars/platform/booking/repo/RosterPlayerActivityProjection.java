package com.softropic.skillars.platform.booking.repo;

import java.time.Instant;

/** One distinct player's latest roster-qualifying booking time for a given coach. See
 * {@link BookingRepository#findRosterPlayerActivityByCoachIdAndStatusIn}. */
public interface RosterPlayerActivityProjection {
    Long getPlayerId();

    Instant getLatestBookingTime();

    /** skillars-12-10: latest booking END (the access-window clock, distinct from the start-based recency above). */
    Instant getLatestBookingEndTime();
}
