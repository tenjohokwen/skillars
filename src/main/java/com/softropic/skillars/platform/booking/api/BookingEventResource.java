package com.softropic.skillars.platform.booking.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.booking.contract.BookingResponse;
import com.softropic.skillars.platform.booking.contract.BookingStatus;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.platform.booking.service.BookingSseService;
import com.softropic.skillars.platform.booking.service.BookingStateMachine;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

import static net.logstash.logback.argument.StructuredArguments.kv;

@Observed(name = "booking.events")
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
@Slf4j
public class BookingEventResource {

    private final BookingService bookingService;
    private final BookingSseService bookingSseService;
    private final BookingStateMachine bookingStateMachine;
    private final CoachProfileRepository coachProfileRepository;
    private final SecurityUtil securityUtil;

    @GetMapping("/{id}/events")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    public ResponseEntity<SseEmitter> subscribeToEvents(@PathVariable UUID id) {
        Long actorUserId = currentUserId();
        Booking booking = bookingService.getBookingOrThrow(id);
        verifyIsParty(booking, actorUserId);

        BookingStatus currentStatus = BookingStatus.valueOf(booking.getStatus());
        SseEmitter emitter = bookingStateMachine.isTerminal(currentStatus)
            ? bookingSseService.subscribeTerminal(booking.getStatus())
            : bookingSseService.subscribe(id, booking.getStatus());
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
        if (isParent) {
            return;
        }
        // Deferred-78 AC5: the throw below is reached only when isParent is also false, so the two
        // branches here can never fire for the parent/admin cases above — only for a genuine
        // unauthorized third party (no coach profile at all) or a coach whose profile exists but
        // doesn't own this booking. Distinguishing them in the log is the point of this AC; the
        // response itself stays the same generic 403 either way.
        Optional<CoachProfile> coach = coachProfileRepository.findByUserId(actorUserId);
        if (coach.isEmpty()) {
            log.warn("Booking-party check failed: actor has no coach profile",
                kv("bookingId", booking.getId()), kv("actorUserId", actorUserId));
            throw new OperationNotAllowedException("Not a party to this booking", SecurityError.MISSING_RIGHTS);
        }
        if (!Objects.equals(coach.get().getId(), booking.getCoachId())) {
            log.warn("Booking-party check failed: actor coach profile does not match booking coach",
                kv("bookingId", booking.getId()), kv("actorUserId", actorUserId),
                kv("actorCoachId", coach.get().getId()), kv("bookingCoachId", booking.getCoachId()));
            throw new OperationNotAllowedException("Not a party to this booking", SecurityError.MISSING_RIGHTS);
        }
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
