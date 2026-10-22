package com.softropic.skillars.platform.booking.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * skillars-12-7 AC1: the owning coach's recap for one completed booking. {@code editable} comes from the
 * same service-level check the PUT uses; {@code editableUntil} is null when the booking has no end time.
 * {@code notEditableReason} is the refusal's error key (the same one the PUT would return) when
 * {@code editable} is false, else null -- so the dialog can tell "too old" from "erased" (code review).
 */
public record SessionRecapResponse(
    UUID bookingId,
    String whatWeDid,
    String whatsNext,
    String whatWentWell,
    boolean editable,
    Instant editableUntil,
    String notEditableReason
) {}
