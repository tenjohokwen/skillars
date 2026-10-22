package com.softropic.skillars.platform.booking.contract;

import java.util.UUID;

/** skillars-12-6 AC2: narrow response for PATCH /api/bookings/{id}/coach-plan-note. */
public record CoachPlanNoteResponse(UUID bookingId, String planNote) {}
