package com.softropic.skillars.platform.admin.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.admin.contract.AdminBookingLookupDto;
import com.softropic.skillars.platform.admin.contract.AdminCancelBookingRequest;
import com.softropic.skillars.platform.admin.contract.AdminCancelBookingResponse;
import com.softropic.skillars.platform.admin.contract.AdminForceCompleteBookingRequest;
import com.softropic.skillars.platform.admin.contract.AdminForceCompleteBookingResponse;
import com.softropic.skillars.platform.admin.service.AdminBookingCancellationService;
import com.softropic.skillars.platform.admin.service.AdminBookingCompletionService;
import com.softropic.skillars.platform.booking.contract.ForceCompleteOutcome;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/bookings")
@RequiredArgsConstructor
@Observed(name = "admin.bookings")
public class AdminBookingResource {

    private final AdminBookingCompletionService adminBookingCompletionService;
    private final AdminBookingCancellationService adminBookingCancellationService;
    private final SecurityUtil securityUtil;

    @GetMapping("/{bookingId}")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "admin.bookings.lookup")
    public ResponseEntity<AdminBookingLookupDto> lookup(@PathVariable UUID bookingId) {
        return ResponseEntity.ok(adminBookingCompletionService.lookup(bookingId));
    }

    @PostMapping("/{bookingId}/force-complete")
    @PreAuthorize(SecurityConstants.HAS_FULL_ADMIN_ROLE)
    @Observed(name = "admin.bookings.forceComplete")
    public ResponseEntity<AdminForceCompleteBookingResponse> forceComplete(
            @PathVariable UUID bookingId,
            @Valid @RequestBody AdminForceCompleteBookingRequest request) {
        // skillars-deferred-153 code review (2026-10-17): the outcome is returned, not swallowed —
        // ALREADY_RESOLVED and NOT_STUCK are successes (AC1: "treat as already-resolved, not an
        // error"), but the admin has to be able to tell them apart from a real force-complete.
        ForceCompleteOutcome outcome =
            adminBookingCompletionService.forceComplete(bookingId, resolveAdminId(), request.note());
        return ResponseEntity.ok(new AdminForceCompleteBookingResponse(outcome));
    }

    /**
     * C6: cancel one booking with the suspension-sweep semantics (full refund of captured money, no coach
     * strike, neutral parent notice). Always 200 with the outcome: CANCELLED, or the refusal reason
     * (LIVE_SESSION / PAYMENT_IN_PROGRESS / NOT_CANCELLABLE) — the force-complete endpoint's contract.
     */
    @PostMapping("/{bookingId}/cancel")
    @PreAuthorize(SecurityConstants.HAS_FULL_ADMIN_ROLE)
    @Observed(name = "admin.bookings.cancel")
    public ResponseEntity<AdminCancelBookingResponse> cancel(
            @PathVariable UUID bookingId,
            @Valid @RequestBody AdminCancelBookingRequest request) {
        return ResponseEntity.ok(new AdminCancelBookingResponse(
            adminBookingCancellationService.cancel(bookingId, resolveAdminId(), request.note())));
    }

    private Long resolveAdminId() {
        Object principal = securityUtil.getCurrentUser();
        if (!(principal instanceof Principal p)) {
            throw new InsufficientAuthenticationException("Unexpected principal type");
        }
        try {
            return Long.parseLong(p.getBusinessId());
        } catch (NumberFormatException e) {
            throw new InsufficientAuthenticationException("Invalid business ID");
        }
    }
}
