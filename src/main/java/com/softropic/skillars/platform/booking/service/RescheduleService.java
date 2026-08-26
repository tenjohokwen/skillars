package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.booking.contract.BookingError;
import com.softropic.skillars.platform.booking.contract.BookingStatus;
import com.softropic.skillars.platform.booking.contract.CreateRescheduleRequest;
import com.softropic.skillars.platform.booking.contract.RescheduleAcceptedEvent;
import com.softropic.skillars.platform.booking.contract.RescheduleDeclinedByParentEvent;
import com.softropic.skillars.platform.booking.contract.RescheduleDeclinedEvent;
import com.softropic.skillars.platform.booking.contract.RescheduleRequestedByCoachEvent;
import com.softropic.skillars.platform.booking.contract.RescheduleRequestedEvent;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.repo.BookingRescheduleRequest;
import com.softropic.skillars.platform.booking.repo.BookingRescheduleRequestRepository;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindow;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindowRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
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
 *
 * <p>skillars-deferred-69 AC5 added a coach-initiated reschedule path, mirroring this codebase's
 * established per-actor-separate-method convention ({@code BookingService.cancelBookingAsParent}/
 * {@code cancelBookingAsCoach}) rather than branching one method by role:
 * {@code requestRescheduleAsCoach}, {@code acceptRescheduleAsParent},
 * {@code declineRescheduleAsParent} are new siblings of the original parent-request/coach-respond
 * methods, sharing validation via {@code validateRescheduleProposal} and
 * {@code acceptRescheduleShared} rather than duplicating the lock-ordering, availability-recheck, and
 * overlap-check logic. {@code BookingError.CANNOT_RESPOND_TO_OWN_PROPOSAL} guards every accept/decline
 * method against the proposer responding to their own proposal.
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
    private final CoachAvailabilityWindowRepository coachAvailabilityWindowRepository;
    private final PessimisticLockRetryer lockRetryer;

    @Transactional
    public void requestReschedule(UUID bookingId, Long parentUserId, CreateRescheduleRequest req) {
        Booking booking = bookingService.getBookingOrThrow(bookingId);

        if (!booking.getParentId().equals(parentUserId)) {
            throw new OperationNotAllowedException("Parent does not own this booking", SecurityError.MISSING_RIGHTS);
        }
        validateRescheduleProposal(bookingId, booking, req);

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
    public void requestRescheduleAsCoach(UUID bookingId, Long coachUserId, CreateRescheduleRequest req) {
        Booking booking = bookingService.getBookingOrThrow(bookingId);
        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));

        if (!booking.getCoachId().equals(coach.getId())) {
            throw new OperationNotAllowedException("Coach does not own this booking", SecurityError.MISSING_RIGHTS);
        }
        validateRescheduleProposal(bookingId, booking, req);

        BookingRescheduleRequest rescheduleRequest = new BookingRescheduleRequest();
        rescheduleRequest.setBookingId(bookingId);
        rescheduleRequest.setProposedBy("COACH");
        rescheduleRequest.setProposedStartTime(req.proposedStartTime());
        rescheduleRequest.setProposedEndTime(req.proposedEndTime());
        rescheduleRepo.save(rescheduleRequest);

        String parentEmail = resolveEmail(booking.getParentId(), bookingId);

        eventPublisher.publishEvent(new RescheduleRequestedByCoachEvent(
            this, bookingId, parentEmail, coach.getDisplayName(),
            booking.getRequestedStartTime(), req.proposedStartTime(),
            booking.getCanonicalTimezone()
        ));
        log.info("Reschedule requested for booking {} by coach {}", bookingId, coachUserId);
    }

    /**
     * Shared validation for a NEW reschedule proposal, regardless of which party is proposing.
     * Story-review Issue #2: nothing here may reference {@code parentUserId}/{@code coachUserId} —
     * the one thing that legitimately differs between the two callers is the ownership check, which
     * stays in each caller, before this method is invoked.
     */
    private void validateRescheduleProposal(UUID bookingId, Booking booking, CreateRescheduleRequest req) {
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
        // UAT.2 AC3: a reschedule is a MOVE, not a resize. Without this, either party reschedules a
        // compliant 60-minute session into an eight-hour one and the whole session-duration rule is
        // bypassed on the third write path.
        //
        // Deliberately compared against the BOOKING'S OWN duration, never against
        // SessionDurationResolver — which is why this service does not inject it. Duration was
        // entirely unconstrained until this story, so bookings already in any UAT database have
        // arbitrary lengths; resolving against the coach's current length would hard-reject a move
        // of a legacy 3-hour session at its own length, behind a generic "something went wrong"
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
        // Deferred-49 AC1: current availability, re-validated at request time — a coach who has
        // since narrowed their hours can reject a reschedule proposal the same way an initial
        // booking request is already blocked, even though the original booking was legitimate
        // when made. Reuses BookingService's own package-private helper rather than a second copy.
        List<CoachAvailabilityWindow> windows = coachAvailabilityWindowRepository.findByCoachId(booking.getCoachId());
        if (!bookingService.isSlotWithinAvailabilityWindow(req.proposedStartTime(), req.proposedEndTime(), windows, booking.getCoachId())) {
            throw new OperationNotAllowedException(
                "Proposed slot is not within coach availability",
                Map.of("proposed start time", req.proposedStartTime(), "proposed end time", req.proposedEndTime()),
                BookingError.SLOT_OUTSIDE_AVAILABILITY);
        }

        rescheduleRepo.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(bookingId, "PENDING")
            .ifPresent(existing -> {
                throw new OperationNotAllowedException(
                    "A pending reschedule request already exists",
                    BookingError.RESCHEDULE_ALREADY_PENDING);
            });
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
        if ("COACH".equals(req.getProposedBy())) {
            throw new OperationNotAllowedException(
                "Coach cannot respond to their own reschedule proposal", BookingError.CANNOT_RESPOND_TO_OWN_PROPOSAL);
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

        acceptRescheduleShared(bookingId, rescheduleId, booking, coach, req);

        String parentEmail = resolveEmail(booking.getParentId(), bookingId);
        String coachEmail = resolveEmail(coach.getUserId(), bookingId);

        eventPublisher.publishEvent(new RescheduleAcceptedEvent(
            this, bookingId, parentEmail, coachEmail, coach.getDisplayName(),
            req.getProposedStartTime(), booking.getCanonicalTimezone()
        ));
        log.info("Reschedule {} accepted for booking {} by coach {}", rescheduleId, bookingId, coachUserId);
    }

    @Transactional
    public void acceptRescheduleAsParent(UUID bookingId, UUID rescheduleId, Long parentUserId) {
        Booking booking = bookingService.getBookingOrThrow(bookingId);
        if (!booking.getParentId().equals(parentUserId)) {
            throw new OperationNotAllowedException("Parent does not own this booking", SecurityError.MISSING_RIGHTS);
        }
        CoachProfile coach = coachProfileRepository.findById(booking.getCoachId())
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));

        BookingRescheduleRequest req = rescheduleRepo.findById(rescheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Reschedule request not found", "reschedule_request"));
        if (!req.getBookingId().equals(bookingId)) {
            throw new ResourceNotFoundException("Reschedule request not found", "reschedule_request");
        }
        if ("PARENT".equals(req.getProposedBy())) {
            throw new OperationNotAllowedException(
                "Parent cannot respond to their own reschedule proposal", BookingError.CANNOT_RESPOND_TO_OWN_PROPOSAL);
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

        acceptRescheduleShared(bookingId, rescheduleId, booking, coach, req);

        // Reused unchanged (skillars-deferred-69 AC5, story-review Issue #10/#11): already carries
        // BOTH parentEmail and coachEmail as independent fields, and BookingEmailListener
        // .onRescheduleAccepted already loops both and filters blanks — fully direction-agnostic
        // today, no change needed for a parent accepting a coach's proposal.
        String parentEmail = resolveEmail(booking.getParentId(), bookingId);
        String coachEmail = resolveEmail(coach.getUserId(), bookingId);

        eventPublisher.publishEvent(new RescheduleAcceptedEvent(
            this, bookingId, parentEmail, coachEmail, coach.getDisplayName(),
            req.getProposedStartTime(), booking.getCanonicalTimezone()
        ));
        log.info("Reschedule {} accepted for booking {} by parent {}", rescheduleId, bookingId, parentUserId);
    }

    /**
     * Shared lock/availability/overlap body for accepting a PENDING, still-reschedulable,
     * still-future proposal — identical regardless of which party is accepting, since the lock
     * target is always the coach's row and the reschedule-request row, never anything
     * ownership-specific. Story-review Issue #3: deliberately NOT {@code @Transactional} — both
     * callers already are, and {@code PessimisticLockRetryer}'s bounded retry is savepoint-based
     * within the caller's existing transaction; adding a transactional boundary here would create a
     * nested-proxy that changes that behavior.
     *
     * <p>{@code booking}, {@code coach}, and {@code req} are managed JPA entities already in the
     * caller's persistence context — the locked re-reads below re-read the SAME managed instances
     * under lock (see the existing {@code entityManager.refresh} comments), so mutations here are
     * visible to the caller through the same object references without needing a return value.
     */
    private void acceptRescheduleShared(UUID bookingId, UUID rescheduleId, Booking booking, CoachProfile coach, BookingRescheduleRequest req) {
        // Deferred-15 AC3. LOCK ORDERING: reschedule row first, coach second. declineReschedule
        // (and declineRescheduleAsParent) take only the reschedule lock, so no path acquires the
        // pair in the opposite order and the two cannot deadlock. A future editor adding a coach
        // lock to either decline method must take it second, after this one.
        //
        // The PENDING check in each caller is a cheap early-out over an unlocked read. Without this
        // locked re-read, a decline committing while this method waits on the coach lock would be
        // silently overwritten: the accept resumes holding a stale in-memory req that still says
        // PENDING, and writes ACCEPTED over the decline. The refresh is what makes the re-read real —
        // findByIdForUpdate is JPQL and the row is already managed from the caller's findById above,
        // so without it Hibernate takes the lock but hands back the same stale instance.
        BookingRescheduleRequest lockedReq = lockRetryer.withBoundedRetry(() -> {
            BookingRescheduleRequest r = rescheduleRepo.findByIdForUpdate(rescheduleId)
                .orElseThrow(() -> new ResourceNotFoundException("Reschedule request not found", "reschedule_request"));
            entityManager.refresh(r, LockModeType.PESSIMISTIC_WRITE);
            return r;
        });
        if (!"PENDING".equals(lockedReq.getStatus())) {
            throw new OperationNotAllowedException(
                "Reschedule request is not in PENDING status", BookingError.RESCHEDULE_NOT_PENDING);
        }

        // Deferred-14 AC4. Reschedule rewrites the booking's time window with no overlap check, so
        // until now the only thing standing between it and a double-booked coach was the V87
        // exclusion constraint — which fires at commit and, in a batched flush, loses the constraint
        // name and surfaces as an unmapped 500 instead of a clean booking.slotUnavailable. Lock the
        // coach so two concurrent reschedules for the same coach serialise, then check the
        // PROPOSED window (not the current one). excludeBookingId is mandatory: this booking is
        // itself in an active status and would otherwise match itself.
        CoachProfile lockedCoach = lockRetryer.withBoundedRetry(() -> {
            CoachProfile c = coachProfileRepository.findByIdForUpdate(coach.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
            // Deferred-15 AC4. The refresh is required, not defensive: findByIdForUpdate is JPQL and this
            // row is already managed from the caller's findByUserId/findById above, so Hibernate takes the
            // DB lock but returns the existing instance with its in-memory state intact — reading
            // getStatus() off it without this would re-check the stale value and could never fire. Same
            // reasoning as BookingService.createBookingRequest.
            entityManager.refresh(c, LockModeType.PESSIMISTIC_WRITE);
            return c;
        });
        if (lockedCoach.getStatus() == CoachProfileStatus.SUSPENDED) {
            throw new OperationNotAllowedException("Coach is suspended",
                Map.of("submitted coach id", coach.getId()), BookingError.COACH_UNAVAILABLE);
        }

        // Deferred-49 AC4: requestReschedule already checked availability when the proposal was
        // made, but a reschedule can sit PENDING indefinitely — the coach may have narrowed their
        // hours since. Re-checked here, at the point the booking's time actually gets finalized, the
        // same way the start-time-in-past and coach-suspension checks above are re-validated at
        // accept time rather than trusted from proposal time. Placed before the overlap check below
        // (not after) so a reschedule outside availability is rejected on that basis even when the
        // slot also happens to be free — matches this method's existing check ordering, where each
        // earlier check gates the next rather than running independently.
        List<CoachAvailabilityWindow> windows = coachAvailabilityWindowRepository.findByCoachId(coach.getId());
        if (!bookingService.isSlotWithinAvailabilityWindow(lockedReq.getProposedStartTime(), lockedReq.getProposedEndTime(), windows, coach.getId())) {
            throw new OperationNotAllowedException(
                "Proposed slot is not within coach availability",
                Map.of("submitted coach id", coach.getId(), "proposed start time", lockedReq.getProposedStartTime(),
                    "proposed end time", lockedReq.getProposedEndTime()),
                BookingError.SLOT_OUTSIDE_AVAILABILITY);
        }

        List<Booking> overlapping = bookingRepository.findOverlappingBookings(
            coach.getId(), lockedReq.getProposedStartTime(), lockedReq.getProposedEndTime(),
            BookingService.ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED, bookingId);
        if (!overlapping.isEmpty()) {
            throw new OperationNotAllowedException(
                "The proposed slot is no longer available — another booking occupies that time",
                Map.of("submitted coach id", coach.getId(), "proposed start time", lockedReq.getProposedStartTime(),
                    "proposed end time", lockedReq.getProposedEndTime()),
                BookingError.SLOT_UNAVAILABLE);
        }

        // Story-review Patch finding: each caller's own start-time-in-past check runs on an
        // UNLOCKED read, before lock acquisition — under contention, waiting for the coach/
        // reschedule locks above can itself take long enough for that same instant to lapse into
        // the past. Re-checked here, immediately before the booking's times are actually
        // persisted, the same way availability and overlap are re-validated post-lock rather than
        // trusted from the caller's pre-lock read.
        if (!lockedReq.getProposedStartTime().isAfter(Instant.now())) {
            throw new OperationNotAllowedException(
                "Proposed start time is no longer in the future", BookingError.START_TIME_IN_PAST);
        }

        booking.setRequestedStartTime(lockedReq.getProposedStartTime());
        booking.setRequestedEndTime(lockedReq.getProposedEndTime());
        try {
            bookingRepository.save(booking);
        } catch (OptimisticLockingFailureException e) {
            throw new OperationNotAllowedException("Booking status changed concurrently — retry", e, BookingError.CONCURRENT_MODIFICATION);
        }
        lockedReq.setStatus("ACCEPTED");
        rescheduleRepo.save(lockedReq);
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
        // see the ordering note in acceptRescheduleShared before adding another.
        BookingRescheduleRequest req = lockRetryer.withBoundedRetry(() -> rescheduleRepo.findByIdForUpdate(rescheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Reschedule request not found", "reschedule_request")));
        if (!req.getBookingId().equals(bookingId)) {
            throw new ResourceNotFoundException("Reschedule request not found", "reschedule_request");
        }
        if ("COACH".equals(req.getProposedBy())) {
            throw new OperationNotAllowedException(
                "Coach cannot respond to their own reschedule proposal", BookingError.CANNOT_RESPOND_TO_OWN_PROPOSAL);
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

    @Transactional
    public void declineRescheduleAsParent(UUID bookingId, UUID rescheduleId, Long parentUserId) {
        Booking booking = bookingService.getBookingOrThrow(bookingId);
        if (!booking.getParentId().equals(parentUserId)) {
            throw new OperationNotAllowedException("Parent does not own this booking", SecurityError.MISSING_RIGHTS);
        }
        CoachProfile coach = coachProfileRepository.findById(booking.getCoachId())
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));

        BookingRescheduleRequest req = lockRetryer.withBoundedRetry(() -> rescheduleRepo.findByIdForUpdate(rescheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Reschedule request not found", "reschedule_request")));
        if (!req.getBookingId().equals(bookingId)) {
            throw new ResourceNotFoundException("Reschedule request not found", "reschedule_request");
        }
        if ("PARENT".equals(req.getProposedBy())) {
            throw new OperationNotAllowedException(
                "Parent cannot respond to their own reschedule proposal", BookingError.CANNOT_RESPOND_TO_OWN_PROPOSAL);
        }
        if (!"PENDING".equals(req.getStatus())) {
            throw new OperationNotAllowedException(
                "Reschedule request is not in PENDING status", BookingError.RESCHEDULE_NOT_PENDING);
        }

        req.setStatus("DECLINED");
        rescheduleRepo.save(req);

        String coachEmail = resolveEmail(coach.getUserId(), bookingId);
        String parentName = userRepository.findById(parentUserId)
            .map(u -> u.getFirstName() + " " + u.getLastName())
            .orElse("Parent");
        eventPublisher.publishEvent(new RescheduleDeclinedByParentEvent(
            this, bookingId, coachEmail, parentName,
            booking.getRequestedStartTime(), booking.getCanonicalTimezone()
        ));
        log.info("Reschedule {} declined for booking {} by parent {}", rescheduleId, bookingId, parentUserId);
    }

    private String resolveEmail(Long userId, UUID bookingId) {
        return userRepository.findById(userId).map(u -> u.getEmail()).orElseGet(() -> {
            log.warn("Could not resolve email for userId={} bookingId={} — notification will be skipped", userId, bookingId);
            return "";
        });
    }
}
