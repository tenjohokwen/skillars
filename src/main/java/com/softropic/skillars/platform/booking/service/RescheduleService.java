package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.booking.contract.BookingError;
import com.softropic.skillars.platform.booking.contract.BookingStatus;
import com.softropic.skillars.platform.booking.contract.CreateRescheduleRequest;
import com.softropic.skillars.platform.booking.contract.RescheduleAcceptedEvent;
import com.softropic.skillars.platform.booking.contract.RescheduleDeclinedEvent;
import com.softropic.skillars.platform.booking.contract.RescheduleRequestedEvent;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.repo.BookingRescheduleRequest;
import com.softropic.skillars.platform.booking.repo.BookingRescheduleRequestRepository;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reschedule request/accept/decline.
 *
 * <p>Deferred-31 AC3: this class used to throw {@code SecurityError.MISSING_RIGHTS} twelve times
 * across its three public methods, so nine distinct state rejections — booking not reschedulable, a
 * reschedule already pending, a request no longer PENDING, a start time in the past, an inverted
 * time range — all reached the parent and the coach as the same "you are not allowed" toast. Only
 * the three ownership checks ({@code requestReschedule}, {@code acceptReschedule},
 * {@code declineReschedule}, one each) are genuine authorization and keep {@code MISSING_RIGHTS}.
 * Everything else carries a {@code BookingError}. All of them still surface as HTTP 403 —
 * {@code OperationNotAllowedException} maps to FORBIDDEN independent of the code it carries — so the
 * split changed the {@code errorKey} and the message, not the status.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RescheduleService {

    private static final Set<String> RESCHEDULABLE_STATUSES = Set.of(
        BookingStatus.CONFIRMED.name(), BookingStatus.UPCOMING.name());

    private final BookingService bookingService;
    private final BookingRepository bookingRepository;
    private final BookingRescheduleRequestRepository rescheduleRepo;
    private final CoachProfileRepository coachProfileRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    // Deferred-15 AC4: needed for the locked re-reads in acceptReschedule — findByIdForUpdate is
    // JPQL and returns an already-managed instance untouched. BookingService injects one the same way.
    private final EntityManager entityManager;

    @Transactional
    public void requestReschedule(UUID bookingId, Long parentUserId, CreateRescheduleRequest req) {
        Booking booking = bookingService.getBookingOrThrow(bookingId);

        if (!booking.getParentId().equals(parentUserId)) {
            throw new OperationNotAllowedException("Parent does not own this booking", SecurityError.MISSING_RIGHTS);
        }
        if (!RESCHEDULABLE_STATUSES.contains(booking.getStatus())) {
            throw new OperationNotAllowedException(
                "Reschedule is only allowed for CONFIRMED or UPCOMING bookings",
                BookingError.BOOKING_NOT_RESCHEDULABLE);
        }
        if (!req.proposedStartTime().isAfter(Instant.now())) {
            throw new OperationNotAllowedException(
                "Proposed start time must be in the future", BookingError.START_TIME_IN_PAST);
        }
        if (!req.proposedEndTime().isAfter(req.proposedStartTime())) {
            throw new OperationNotAllowedException(
                "Proposed end time must be after start time", BookingError.INVALID_TIME_RANGE);
        }
        // UAT.2 AC3: a reschedule is a MOVE, not a resize. Without this, a parent reschedules a
        // compliant 60-minute session into an eight-hour one and the whole session-duration rule is
        // bypassed on the third write path.
        //
        // Deliberately compared against the BOOKING'S OWN duration, never against
        // SessionDurationResolver — which is why this service does not inject it. Duration was
        // entirely unconstrained until this story, so bookings already in any UAT database have
        // arbitrary lengths; resolving against the coach's current length would hard-reject a parent
        // moving a legacy 3-hour session at its own length, behind a generic "something went wrong"
        // toast (booking.invalidSessionDuration resolves in no bundle). Same reasoning already
        // exempts BookingDuplicationService; the two rules must not diverge.
        Duration originalDuration =
            Duration.between(booking.getRequestedStartTime(), booking.getRequestedEndTime());
        Duration proposedDuration =
            Duration.between(req.proposedStartTime(), req.proposedEndTime());
        if (!proposedDuration.equals(originalDuration)) {
            throw new OperationNotAllowedException(
                "A reschedule must keep the session's original length",
                Map.of("proposed minutes", proposedDuration.toMinutes(),
                    "original minutes", originalDuration.toMinutes()),
                BookingError.INVALID_SESSION_DURATION);
        }
        rescheduleRepo.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(bookingId, "PENDING")
            .ifPresent(existing -> {
                throw new OperationNotAllowedException(
                    "A pending reschedule request already exists",
                    BookingError.RESCHEDULE_ALREADY_PENDING);
            });

        BookingRescheduleRequest rescheduleRequest = new BookingRescheduleRequest();
        rescheduleRequest.setBookingId(bookingId);
        rescheduleRequest.setProposedBy("PARENT");
        rescheduleRequest.setProposedStartTime(req.proposedStartTime());
        rescheduleRequest.setProposedEndTime(req.proposedEndTime());
        rescheduleRepo.save(rescheduleRequest);

        CoachProfile coach = coachProfileRepository.findById(booking.getCoachId())
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
        String coachEmail = resolveEmail(coach.getUserId(), bookingId);
        String parentName = userRepository.findById(parentUserId)
            .map(u -> u.getFirstName() + " " + u.getLastName())
            .orElse("Parent");

        eventPublisher.publishEvent(new RescheduleRequestedEvent(
            this, bookingId, coachEmail, parentName,
            booking.getRequestedStartTime(), req.proposedStartTime(),
            booking.getCanonicalTimezone()
        ));
        log.info("Reschedule requested for booking {} by parent {}", bookingId, parentUserId);
    }

    @Transactional
    public void acceptReschedule(UUID bookingId, UUID rescheduleId, Long coachUserId) {
        Booking booking = bookingService.getBookingOrThrow(bookingId);
        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));

        if (!booking.getCoachId().equals(coach.getId())) {
            throw new OperationNotAllowedException("Coach does not own this booking", SecurityError.MISSING_RIGHTS);
        }

        BookingRescheduleRequest req = rescheduleRepo.findById(rescheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Reschedule request not found", "reschedule_request"));
        if (!req.getBookingId().equals(bookingId)) {
            throw new ResourceNotFoundException("Reschedule request not found", "reschedule_request");
        }
        if (!"PENDING".equals(req.getStatus())) {
            throw new OperationNotAllowedException(
                "Reschedule request is not in PENDING status", BookingError.RESCHEDULE_NOT_PENDING);
        }
        if (!RESCHEDULABLE_STATUSES.contains(booking.getStatus())) {
            throw new OperationNotAllowedException(
                "Booking is no longer in a reschedulable state",
                BookingError.BOOKING_NOT_RESCHEDULABLE);
        }
        if (!req.getProposedStartTime().isAfter(Instant.now())) {
            throw new OperationNotAllowedException(
                "Proposed start time is no longer in the future", BookingError.START_TIME_IN_PAST);
        }

        // Deferred-15 AC3. LOCK ORDERING: reschedule row first, coach second. declineReschedule
        // takes only the reschedule lock, so no path acquires the pair in the opposite order and the
        // two cannot deadlock. A future editor adding a coach lock to declineReschedule must take it
        // second, after this one.
        //
        // The PENDING check above is a cheap early-out over an unlocked read. Without this locked
        // re-read, a decline committing while this method waits on the coach lock would be silently
        // overwritten: the accept resumes holding a stale in-memory req that still says PENDING,
        // and writes ACCEPTED over the coach's decline. The refresh is what makes the re-read real —
        // findByIdForUpdate is JPQL and the row is already managed from the findById above, so
        // without it Hibernate takes the lock but hands back the same stale instance.
        BookingRescheduleRequest lockedReq = rescheduleRepo.findByIdForUpdate(rescheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Reschedule request not found", "reschedule_request"));
        entityManager.refresh(lockedReq, LockModeType.PESSIMISTIC_WRITE);
        if (!"PENDING".equals(lockedReq.getStatus())) {
            throw new OperationNotAllowedException(
                "Reschedule request is not in PENDING status", BookingError.RESCHEDULE_NOT_PENDING);
        }
        req = lockedReq;

        // Deferred-14 AC4. Reschedule rewrites the booking's time window with no overlap check, so
        // until now the only thing standing between it and a double-booked coach was the V87
        // exclusion constraint — which fires at commit and, in a batched flush, loses the constraint
        // name and surfaces as an unmapped 500 instead of a clean booking.slotUnavailable. Lock the
        // coach so two concurrent reschedules for the same coach serialise, then check the
        // PROPOSED window (not the current one). excludeBookingId is mandatory: this booking is
        // itself in an active status and would otherwise match itself.
        CoachProfile lockedCoach = coachProfileRepository.findByIdForUpdate(coach.getId())
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
        // Deferred-15 AC4. The refresh is required, not defensive: findByIdForUpdate is JPQL and this
        // row is already managed from the findByUserId above, so Hibernate takes the DB lock but
        // returns the existing instance with its in-memory state intact — reading getStatus() off it
        // without this would re-check the stale value and could never fire. Same reasoning as
        // BookingService.createBookingRequest.
        entityManager.refresh(lockedCoach, LockModeType.PESSIMISTIC_WRITE);
        if (lockedCoach.getStatus() == CoachProfileStatus.SUSPENDED) {
            throw new OperationNotAllowedException("Coach is suspended",
                Map.of("submitted coach id", coach.getId()), BookingError.COACH_UNAVAILABLE);
        }

        List<Booking> overlapping = bookingRepository.findOverlappingBookings(
            coach.getId(), req.getProposedStartTime(), req.getProposedEndTime(),
            BookingService.ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED, bookingId);
        if (!overlapping.isEmpty()) {
            throw new OperationNotAllowedException(
                "The proposed slot is no longer available — another booking occupies that time",
                Map.of("submitted coach id", coach.getId(), "proposed start time", req.getProposedStartTime(),
                    "proposed end time", req.getProposedEndTime()),
                BookingError.SLOT_UNAVAILABLE);
        }

        booking.setRequestedStartTime(req.getProposedStartTime());
        booking.setRequestedEndTime(req.getProposedEndTime());
        bookingRepository.save(booking);
        req.setStatus("ACCEPTED");
        rescheduleRepo.save(req);

        String parentEmail = resolveEmail(booking.getParentId(), bookingId);
        String coachEmail = resolveEmail(coach.getUserId(), bookingId);

        eventPublisher.publishEvent(new RescheduleAcceptedEvent(
            this, bookingId, parentEmail, coachEmail, coach.getDisplayName(),
            req.getProposedStartTime(), booking.getCanonicalTimezone()
        ));
        log.info("Reschedule {} accepted for booking {} by coach {}", rescheduleId, bookingId, coachUserId);
    }

    @Transactional
    public void declineReschedule(UUID bookingId, UUID rescheduleId, Long coachUserId) {
        Booking booking = bookingService.getBookingOrThrow(bookingId);
        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));

        if (!booking.getCoachId().equals(coach.getId())) {
            throw new OperationNotAllowedException("Coach does not own this booking", SecurityError.MISSING_RIGHTS);
        }

        // Deferred-15 AC3: locked read, so this and acceptReschedule are mutually exclusive rather
        // than both reading PENDING and both writing. This is the only lock this method takes —
        // see the ordering note in acceptReschedule before adding another.
        BookingRescheduleRequest req = rescheduleRepo.findByIdForUpdate(rescheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Reschedule request not found", "reschedule_request"));
        if (!req.getBookingId().equals(bookingId)) {
            throw new ResourceNotFoundException("Reschedule request not found", "reschedule_request");
        }
        if (!"PENDING".equals(req.getStatus())) {
            throw new OperationNotAllowedException(
                "Reschedule request is not in PENDING status", BookingError.RESCHEDULE_NOT_PENDING);
        }

        req.setStatus("DECLINED");
        rescheduleRepo.save(req);

        String parentEmail = resolveEmail(booking.getParentId(), bookingId);
        eventPublisher.publishEvent(new RescheduleDeclinedEvent(
            this, bookingId, parentEmail, coach.getDisplayName(),
            booking.getRequestedStartTime(), booking.getCanonicalTimezone()
        ));
        log.info("Reschedule {} declined for booking {} by coach {}", rescheduleId, bookingId, coachUserId);
    }

    private String resolveEmail(Long userId, UUID bookingId) {
        return userRepository.findById(userId).map(u -> u.getEmail()).orElseGet(() -> {
            log.warn("Could not resolve email for userId={} bookingId={} — notification will be skipped", userId, bookingId);
            return "";
        });
    }
}
