package com.softropic.skillars.platform.booking.contract;

import java.util.UUID;

/**
 * skillars-deferred-162 AC6 (D162-A): booking's read-only view of whether a dispute locks a session's recap.
 * Implemented by the {@code admin} module, which owns disputes; {@code booking} depends only on this interface,
 * so the module direction stays admin -> booking.
 * <p>
 * Locked while any dispute on the booking is active (status not {@code RESOLVED}/{@code DISMISSED}), and after
 * one is {@code RESOLVED} with a resolution other than {@code NO_ACTION} (money returned or coach warned).
 * {@code DISMISSED} and {@code NO_ACTION} leave the recap editable (D162-A1). Booking status is never touched.
 * {@code BookingRepository}'s missing-recap nudge queries repeat this predicate in JPQL; keep the two identical.
 */
public interface BookingDisputeLookup {

    boolean isRecapLockedByDispute(UUID bookingId);
}
