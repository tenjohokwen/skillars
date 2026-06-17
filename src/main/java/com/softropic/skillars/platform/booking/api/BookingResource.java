package com.softropic.skillars.platform.booking.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.booking.contract.BookingResponse;
import com.softropic.skillars.platform.booking.contract.CoachInboxResponse;
import com.softropic.skillars.platform.booking.contract.CreateBookingRequest;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Observed(name = "booking.requests")
@RestController
@RequestMapping("/api/bookings/requests")
@RequiredArgsConstructor
public class BookingResource {

    private final BookingService bookingService;
    private final SecurityUtil securityUtil;

    @PostMapping
    @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
    public ResponseEntity<BookingResponse> createBookingRequest(@RequestBody @Valid CreateBookingRequest req) {
        BookingResponse response = bookingService.createBookingRequest(currentParentId(), req);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
    public ResponseEntity<List<BookingResponse>> getParentBookings() {
        return ResponseEntity.ok(bookingService.getParentBookings(currentParentId()));
    }

    // Declared before /{id}/accept and /{id}/decline to avoid Spring path-matching ambiguity
    @GetMapping("/coach")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<CoachInboxResponse> getCoachBookingRequests() {
        return ResponseEntity.ok(bookingService.getCoachBookingRequests(currentCoachUserId()));
    }

    @PutMapping("/{id}/accept")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<BookingResponse> acceptBooking(@PathVariable UUID id) {
        return ResponseEntity.ok(bookingService.acceptBooking(id, currentCoachUserId()));
    }

    @PutMapping("/{id}/decline")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> declineBooking(@PathVariable UUID id) {
        bookingService.declineBooking(id, currentCoachUserId());
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
