package com.softropic.skillars.platform.booking.contract;

import jakarta.validation.constraints.Size;

/** skillars-12-6 AC2: null/blank clears a previously-set coach plan note. */
public record UpdateCoachPlanNoteRequest(
    @Size(max = 500) String planNote
) {
}
