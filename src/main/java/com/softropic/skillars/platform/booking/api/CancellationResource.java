package com.softropic.skillars.platform.booking.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.booking.contract.CancelCoachRequest;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Observed(name = "booking.cancellation")
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class CancellationResource {

    private final BookingService bookingService;
    private final SecurityUtil securityUtil;

    @PostMapping("/{id}/cancel")
    @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
    public ResponseEntity<Void> cancelBooking(@PathVariable UUID id) {
        bookingService.cancelBookingAsParent(id, currentParentId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/coach-cancel")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> coachCancelBooking(@PathVariable UUID id,
                                                    @RequestBody CancelCoachRequest req) {
        bookingService.cancelBookingAsCoach(id, currentCoachUserId(), req.cancelReason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/no-show-player")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> recordNoShowPlayer(@PathVariable UUID id) {
        bookingService.recordNoShowPlayer(id, currentCoachUserId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/no-show-coach")
    @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
    public ResponseEntity<Void> recordNoShowCoach(@PathVariable UUID id) {
        bookingService.recordNoShowCoach(id, currentParentId());
        return ResponseEntity.noContent().build();
    }

    private Long currentParentId() {
        String businessId = ((Principal) securityUtil.getCurrentUser()).getBusinessId();
        if (businessId == null || businessId.isBlank()) {
            throw new InsufficientAuthenticationException("Principal has no business ID");
        }
        try {
            return Long.parseLong(businessId);
        } catch (NumberFormatException e) {
            throw new InsufficientAuthenticationException("Invalid business ID format in principal");
        }
    }

    private Long currentCoachUserId() {
        String businessId = ((Principal) securityUtil.getCurrentUser()).getBusinessId();
        if (businessId == null || businessId.isBlank()) {
            throw new InsufficientAuthenticationException("Principal has no business ID");
        }
        try {
            return Long.parseLong(businessId);
        } catch (NumberFormatException e) {
            throw new InsufficientAuthenticationException("Invalid business ID format in principal");
        }
    }
}
