package com.softropic.skillars.platform.booking.contract;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public record CreateBookingRequest(
    @NotNull UUID coachId,
    @NotNull Long playerId,
    @NotNull @Future Instant requestedStartTime,
    @NotNull Instant requestedEndTime,
    @Size(max = 500) String notes,
    UUID sessionPackPurchaseId,
    String availabilitySignature
) {

    /**
     * Deferred-14 AC6. A malformed time window is a bad request, not a security violation. The
     * service layer still rejects it (defence in depth for non-HTTP callers) but does so with
     * SecurityError.MISSING_RIGHTS, which surfaces as 403 — misleading for the caller and noise in
     * any 4xx-by-reason breakdown. Bean validation short-circuits before the service is reached.
     *
     * <p>Returns true when either bound is null so that a null field reports only its @NotNull
     * violation rather than two errors for one mistake.
     */
    @AssertTrue(message = "requestedEndTime must be after requestedStartTime")
    public boolean isEndAfterStart() {
        return requestedStartTime == null
            || requestedEndTime == null
            || requestedEndTime.isAfter(requestedStartTime);
    }
}
