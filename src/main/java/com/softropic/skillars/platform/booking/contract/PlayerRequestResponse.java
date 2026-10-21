package com.softropic.skillars.platform.booking.contract;

import java.util.UUID;

// skillars-deferred-156 AC3: PATCH /api/bookings/{id}/player-request previously returned a
// BookingResponse with 4 permanently-null fields (parentName, pendingReschedule, batchId,
// batchSize) — this narrow record carries only what this one endpoint actually populates.
public record PlayerRequestResponse(UUID bookingId, String playerRequest) {}
