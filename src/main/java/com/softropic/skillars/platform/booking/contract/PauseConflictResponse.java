package com.softropic.skillars.platform.booking.contract;

import java.time.Instant;
import java.util.List;

public record PauseConflictResponse(
    boolean pauseApplied,
    List<ConflictingBookingItem> conflictingBookings,
    Instant newExpiresAt
) {}
