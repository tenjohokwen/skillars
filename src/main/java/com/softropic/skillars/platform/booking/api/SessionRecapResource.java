package com.softropic.skillars.platform.booking.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.booking.contract.MissingRecapsResponse;
import com.softropic.skillars.platform.booking.contract.SessionRecapResponse;
import com.softropic.skillars.platform.booking.contract.UpdateSessionRecapRequest;
import com.softropic.skillars.platform.booking.service.BookingCompletionService;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * skillars-12-7 AC1/AC2/AC8: read, correct and find missing session recaps. Own small resource (mirrors
 * {@link CoachPlanNoteResource}); every method is coach-only and the service re-checks booking ownership.
 */
@Observed(name = "booking.sessionRecap")
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class SessionRecapResource {

    private final BookingCompletionService bookingCompletionService;
    private final SecurityUtil securityUtil;

    @GetMapping("/{id}/recap")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<SessionRecapResponse> getRecap(@PathVariable UUID id) {
        return ResponseEntity.ok(bookingCompletionService.getRecap(id, securityUtil.requireCurrentUserId()));
    }

    @PutMapping("/{id}/recap")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<SessionRecapResponse> updateRecap(
            @PathVariable UUID id, @RequestBody @Valid UpdateSessionRecapRequest req) {
        return ResponseEntity.ok(bookingCompletionService.updateRecap(id, securityUtil.requireCurrentUserId(), req));
    }

    @GetMapping("/coaches/me/recaps-missing")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<MissingRecapsResponse> getMissingRecaps() {
        return ResponseEntity.ok(bookingCompletionService.getMissingRecaps(securityUtil.requireCurrentUserId()));
    }
}
