package com.softropic.skillars.platform.booking.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.booking.contract.BookingResponse;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.platform.booking.service.BookingSseService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Observed(name = "booking.events")
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingEventResource {

    private final BookingService bookingService;
    private final BookingSseService bookingSseService;
    private final CoachProfileRepository coachProfileRepository;
    private final SecurityUtil securityUtil;

    @GetMapping("/{id}/events")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    public ResponseEntity<SseEmitter> subscribeToEvents(@PathVariable UUID id) {
        Long actorUserId = currentUserId();
        Booking booking = bookingService.getBookingOrThrow(id);
        verifyIsParty(booking, actorUserId);

        SseEmitter emitter = bookingSseService.subscribe(id, booking.getStatus());
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(emitter);
    }

    @GetMapping("/{id}")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    public ResponseEntity<BookingResponse> getBooking(@PathVariable UUID id) {
        Long actorUserId = currentUserId();
        Booking booking = bookingService.getBookingOrThrow(id);
        verifyIsParty(booking, actorUserId);
        return ResponseEntity.ok(bookingService.toBookingResponse(booking));
    }

    private void verifyIsParty(Booking booking, Long actorUserId) {
        if (securityUtil.isAdmin()) {
            return;
        }
        boolean isParent = Objects.equals(booking.getParentId(), actorUserId);
        boolean isCoach = isCoachParty(booking.getCoachId(), actorUserId);
        if (!isParent && !isCoach) {
            throw new OperationNotAllowedException("Not a party to this booking", SecurityError.MISSING_RIGHTS);
        }
    }

    private boolean isCoachParty(UUID coachId, Long actorUserId) {
        Optional<CoachProfile> coach = coachProfileRepository.findByUserId(actorUserId);
        return coach.map(c -> Objects.equals(c.getId(), coachId)).orElse(false);
    }

    private Long currentUserId() {
        Object principal = securityUtil.getCurrentUser();
        if (!(principal instanceof Principal p)) {
            throw new InsufficientAuthenticationException("Unexpected principal type: " +
                (principal == null ? "null" : principal.getClass().getName()));
        }
        try {
            return Long.parseLong(p.getBusinessId());
        } catch (NumberFormatException e) {
            throw new InsufficientAuthenticationException("Invalid business ID format in principal");
        }
    }
}
