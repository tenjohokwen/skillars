package com.softropic.skillars.platform.booking.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.booking.contract.AvailabilityBlockResponse;
import com.softropic.skillars.platform.booking.contract.AvailabilityWindowResponse;
import com.softropic.skillars.platform.booking.contract.CoachAvailabilityResponse;
import com.softropic.skillars.platform.booking.contract.CreateBlockRequest;
import com.softropic.skillars.platform.booking.contract.CreateWindowRequest;
import com.softropic.skillars.platform.booking.contract.UpdateWindowRequest;
import com.softropic.skillars.platform.booking.service.AvailabilityService;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@Observed(name = "booking.availability")
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class AvailabilityResource {

    private final AvailabilityService availabilityService;
    private final SecurityUtil securityUtil;

    @GetMapping("/coaches/{coachId}/availability")
    @PreAuthorize(SecurityConstants.HAS_ANY_ROLE)
    public ResponseEntity<CoachAvailabilityResponse> getAvailability(
            @PathVariable UUID coachId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {
        return ResponseEntity.ok(availabilityService.getAvailabilityCalendar(coachId, weekStart));
    }

    @PostMapping("/coaches/me/availability/windows")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<AvailabilityWindowResponse> addWindow(
            @RequestBody @Valid CreateWindowRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(availabilityService.addWindow(currentUserId(), req));
    }

    @PutMapping("/coaches/me/availability/windows/{id}")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<AvailabilityWindowResponse> updateWindow(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateWindowRequest req) {
        return ResponseEntity.ok(availabilityService.updateWindow(currentUserId(), id, req));
    }

    @DeleteMapping("/coaches/me/availability/windows/{id}")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> deleteWindow(@PathVariable UUID id) {
        availabilityService.deleteWindow(currentUserId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/coaches/me/availability/blocks")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<AvailabilityBlockResponse> addBlock(
            @RequestBody @Valid CreateBlockRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(availabilityService.addBlock(currentUserId(), req));
    }

    @DeleteMapping("/coaches/me/availability/blocks/{id}")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> deleteBlock(@PathVariable UUID id) {
        availabilityService.deleteBlock(currentUserId(), id);
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
            throw new InsufficientAuthenticationException("Invalid business ID in principal: " + businessId);
        }
    }
}
