package com.softropic.skillars.platform.booking.contract;

import java.time.Instant;
import java.util.UUID;

public record ScheduleBookingItem(
    UUID bookingId,
    Long playerId,
    String playerName,
    Instant requestedStartTime,
    Instant requestedEndTime,
    String status,
    String canonicalTimezone,
    RescheduleRequestResponse pendingReschedule,
    // skillars-12-7 AC7: has the wrap-up row been written, and does it carry any recap text. Null on
    // the paths that don't compute them (parent schedule etc. do not use this record).
    Boolean wrapUpSubmitted,
    Boolean hasRecap,
    // skillars-12-7 code review (Decision 2): the session was started live, so a resumed wrap-up must be
    // submitted LIVE rather than QUICK.
    Boolean startedLive
) {}
