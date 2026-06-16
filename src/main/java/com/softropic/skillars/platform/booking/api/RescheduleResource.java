package com.softropic.skillars.platform.booking.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.booking.contract.CreateRescheduleRequest;
import com.softropic.skillars.platform.booking.service.BookingDuplicationService;
import com.softropic.skillars.platform.booking.service.RescheduleService;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Observed(name = "booking.reschedule")
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class RescheduleResource {

    private final RescheduleService rescheduleService;
    private final BookingDuplicationService duplicationService;
    private final SecurityUtil securityUtil;

    @PostMapping("/{id}/reschedule")
    @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
    public ResponseEntity<Void> requestReschedule(@PathVariable UUID id,
                                                   @Valid @RequestBody CreateRescheduleRequest req) {
        rescheduleService.requestReschedule(id, currentUserId(), req);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/reschedule/{rescheduleId}/accept")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> acceptReschedule(@PathVariable UUID id,
                                                  @PathVariable UUID rescheduleId) {
        rescheduleService.acceptReschedule(id, rescheduleId, currentUserId());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/reschedule/{rescheduleId}/decline")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> declineReschedule(@PathVariable UUID id,
                                                   @PathVariable UUID rescheduleId) {
        rescheduleService.declineReschedule(id, rescheduleId, currentUserId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/duplicate-next-week")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> duplicateNextWeek(@PathVariable UUID id) {
        duplicationService.duplicateNextWeek(id, currentUserId());
        return ResponseEntity.noContent().build();
    }

    private Long currentUserId() {
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
