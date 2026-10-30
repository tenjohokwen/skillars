package com.softropic.skillars.platform.booking.contract;

import java.time.Instant;
import java.util.UUID;

public record BookingResponse(
    UUID id,
    Long playerId,
    String playerName,
    UUID coachId,
    String coachDisplayName,
    Instant requestedStartTime,
    Instant requestedEndTime,
    String status,
    String canonicalTimezone,
    String notes,
    Instant createdAt,
    String parentName,
    RescheduleRequestResponse pendingReschedule,
    UUID batchId,
    Integer batchSize,
    String playerRequest,
    // feat/coach-workflow-gaps A23: true when the booking has a CAPTURED payment, i.e. the payer's
    // receipt endpoint will render it. Only computed for the payer's own list (getParentBookings);
    // false everywhere else.
    boolean receiptAvailable,
    // feat/payment-forfeit-payouts-and-clawback: when the payer may report a coach no-show (start + the
    // late-cancel grace) — BookingService.recordNoShowCoach refuses earlier. Display hint; the server decides.
    Instant coachNoShowReportableAt,
    // fix/booking-request-consent: a coach-proposed booking ("repeat next week"). While REQUESTED, only the
    // parent can act on it (confirm or decline); the coach sees it as awaiting parent confirmation.
    boolean proposedByCoach,
    // fix/audit3-completion-dispute-integrity: the coach pressed Start (liveStartedAt). A started session awaiting
    // the family's confirmation can be disputed but not reported as a coach no-show
    // (BookingService.recordNoShowCoach) — a display hint; the server decides.
    boolean liveStarted
) {}
