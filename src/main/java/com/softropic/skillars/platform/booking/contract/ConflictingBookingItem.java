package com.softropic.skillars.platform.booking.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * A booking a pack pause would cancel. fix/payment-pack-integrity: {@code cancellable} is false for a paid
 * booking inside the 24h cancellation notice window — a pause cannot cancel it (that would turn a forfeit
 * into a refund), so the pause is refused while such a booking is in the window.
 */
public record ConflictingBookingItem(
    UUID id,
    Instant requestedStartTime,
    Instant requestedEndTime,
    String status,
    String canonicalTimezone,
    boolean cancellable
) {}
