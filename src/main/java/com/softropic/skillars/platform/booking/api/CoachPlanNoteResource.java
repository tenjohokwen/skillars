package com.softropic.skillars.platform.booking.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.booking.contract.CoachPlanNoteResponse;
import com.softropic.skillars.platform.booking.contract.UpdateCoachPlanNoteRequest;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * skillars-12-6 AC2: the coach's private per-booking plan note. Own small resource under
 * {@code /api/bookings}, mirroring {@link PlayerRequestResource}; the caller is a COACH, so ownership
 * is resolved via the coach profile (see {@code BookingService.setCoachPlanNote}).
 */
@Observed(name = "booking.coachPlanNote")
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class CoachPlanNoteResource {

    private final BookingService bookingService;
    private final SecurityUtil securityUtil;

    @PatchMapping("/{id}/coach-plan-note")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<CoachPlanNoteResponse> setCoachPlanNote(
            @PathVariable UUID id, @RequestBody @Valid UpdateCoachPlanNoteRequest req) {
        return ResponseEntity.ok(bookingService.setCoachPlanNote(id, currentCallerId(), req.planNote()));
    }

    private Long currentCallerId() {
        return securityUtil.requireCurrentUserId();
    }
}
