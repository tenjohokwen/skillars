package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.booking.contract.ActorRole;
import com.softropic.skillars.platform.booking.contract.BatchBookingAcceptedEvent;
import com.softropic.skillars.platform.booking.contract.BatchBookingCreatedResponse;
import com.softropic.skillars.platform.booking.contract.BatchBookingRequestedEvent;
import com.softropic.skillars.platform.booking.contract.BatchRuleViolationException;
import com.softropic.skillars.platform.booking.contract.BatchSlot;
import com.softropic.skillars.platform.booking.contract.BookingError;
import com.softropic.skillars.platform.booking.contract.CreateBatchRequest;
import com.softropic.skillars.platform.booking.contract.TransitionContext;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingBatch;
import com.softropic.skillars.platform.booking.repo.BookingBatchRepository;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindow;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindowRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingBatchService {

    private final BookingBatchRepository batchRepository;
    private final BookingRepository bookingRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final PlayerProfileRepository playerProfileRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ConfigService configService;
    private final BookingService bookingService;
    private final SessionDurationResolver sessionDurationResolver;
    private final CoachAvailabilityWindowRepository coachAvailabilityWindowRepository;
    private final PlatformTransactionManager transactionManager;

    /**
     * REQUIRES_NEW per booking, mirroring {@code PaymentLifecycleService.perBookingTx}. A failure in
     * one booking's accept must not mark the enclosing transaction rollback-only, which is what made
     * acceptAll's per-booking catch unable to actually skip a booking.
     */
    private TransactionTemplate perBookingTx;

    /** Carries the batch-status write and the settlement event, together, in one commit. */
    private TransactionTemplate trailingTx;

    @PostConstruct
    void initTransactionTemplates() {
        perBookingTx = new TransactionTemplate(transactionManager);
        perBookingTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        trailingTx = new TransactionTemplate(transactionManager);
        trailingTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private static final Set<String> POST_ACCEPTANCE_STATUSES = Set.of(
        "ACCEPTED", "PAYMENT_PENDING", "CONFIRMED", "UPCOMING", "IN_PROGRESS",
        "COMPLETED_PENDING_CONFIRMATION", "COMPLETED"
    );

    public int getMaxBatchSize() {
        return (int) configService.getLong("booking.batch.maxSize");
    }

    @Transactional
    public BatchBookingCreatedResponse createBatch(Long parentId, CreateBatchRequest req) {
        int maxSize = (int) configService.getLong("booking.batch.maxSize");
        if (req.slots().size() > maxSize) {
            throw new BatchRuleViolationException("booking.batchSizeExceeded");
        }

        PlayerProfile player = playerProfileRepository.findById(req.playerId())
            .orElseThrow(() -> new ResourceNotFoundException("Player not found", "player_profile"));
        if (player.getParentId() != null) {
            if (!Objects.equals(player.getParentId(), parentId)) {
                throw new OperationNotAllowedException("Parent does not own this player", SecurityError.MISSING_RIGHTS);
            }
        } else {
            if (!Objects.equals(player.getUserId(), parentId)) {
                throw new OperationNotAllowedException("Player does not own this profile", SecurityError.MISSING_RIGHTS);
            }
        }

        CoachProfile coach = coachProfileRepository.findById(req.coachId())
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
        if (coach.getStatus() != CoachProfileStatus.ACTIVE) {
            throw new OperationNotAllowedException("Coach profile is not active", BookingError.COACH_UNAVAILABLE);
        }

        // UAT.2 AC4: both resolved ONCE for the whole batch, not per slot — @Size(max = 10) on
        // CreateBatchRequest would otherwise multiply a per-slot lookup by ten.
        Duration requiredDuration = sessionDurationResolver.resolve(req.coachId());
        List<CoachAvailabilityWindow> windows =
            coachAvailabilityWindowRepository.findByCoachId(req.coachId());

        for (BatchSlot slot : req.slots()) {
            if (!slot.requestedStartTime().isAfter(Instant.now())) {
                throw new OperationNotAllowedException("Requested start time must be in the future", BookingError.START_TIME_IN_PAST);
            }
            if (!slot.requestedEndTime().isAfter(slot.requestedStartTime())) {
                throw new OperationNotAllowedException("Requested end time must be after start time", BookingError.INVALID_TIME_RANGE);
            }
            Duration slotDuration =
                Duration.between(slot.requestedStartTime(), slot.requestedEndTime());
            if (!slotDuration.equals(requiredDuration)) {
                throw new OperationNotAllowedException(
                    "Requested session length does not match this coach's session length",
                    Map.of("requested minutes", slotDuration.toMinutes(),
                        "required minutes", requiredDuration.toMinutes()),
                    BookingError.INVALID_SESSION_DURATION);
            }
            // The availability-window check this path has never had. BookingService's method is
            // called directly rather than copied — a second copy would drift from its cross-midnight
            // anchoring and invalid-timezone handling.
            if (!bookingService.isSlotWithinAvailabilityWindow(
                    slot.requestedStartTime(), slot.requestedEndTime(), windows)) {
                throw new OperationNotAllowedException(
                    "Requested slot is not within coach availability",
                    Map.of("requested start time", slot.requestedStartTime(),
                        "requested end time", slot.requestedEndTime()),
                    BookingError.SLOT_OUTSIDE_AVAILABILITY);
            }
        }

        long distinctStartTimes = req.slots().stream()
            .map(BatchSlot::requestedStartTime)
            .distinct()
            .count();
        if (distinctStartTimes != req.slots().size()) {
            throw new BatchRuleViolationException("booking.duplicateSlotStartTime");
        }

        // Distinct start times do not imply non-overlapping: 09:00-10:00 and 09:30-10:30 both pass
        // the check above. Kept alongside it rather than replacing it — the duplicate-start message
        // is clearer for the common double-click case, which is exactly what
        // booking.duplicateSlotStartTime already means.
        //
        // Comparing each slot only against its IMMEDIATE predecessor is sufficient for arbitrary
        // durations, not just for the equal ones the duration check above happens to guarantee:
        // the loop above has already rejected any slot whose end is not after its start, so once
        // every adjacent pair satisfies start[i] >= end[i-1] the ends are strictly increasing, and
        // a slot cannot overlap anything earlier than its predecessor. Do not "fix" this into a
        // running-maximum-end comparison — it would be equivalent, just harder to read.
        List<BatchSlot> sortedSlots = req.slots().stream()
            .sorted(Comparator.comparing(BatchSlot::requestedStartTime))
            .toList();
        for (int i = 1; i < sortedSlots.size(); i++) {
            if (sortedSlots.get(i).requestedStartTime()
                    .isBefore(sortedSlots.get(i - 1).requestedEndTime())) {
                throw new BatchRuleViolationException("booking.overlappingSlots");
            }
        }

        BookingBatch batch = new BookingBatch();
        batch.setParentId(parentId);
        batch.setCoachId(req.coachId());
        batch.setRequestedCount(req.slots().size());
        batch.setTotalAmount(req.totalAmount() != null ? req.totalAmount() : BigDecimal.ZERO);
        batchRepository.save(batch);

        String canonicalTimezone = coach.getCanonicalTimezone();
        List<Instant> sessionDates = new ArrayList<>();

        for (BatchSlot slot : req.slots()) {
            Booking booking = new Booking();
            booking.setStatus("REQUESTED");
            booking.setParentId(parentId);
            booking.setPlayerId(req.playerId());
            booking.setCoachId(req.coachId());
            booking.setRequestedStartTime(slot.requestedStartTime());
            booking.setRequestedEndTime(slot.requestedEndTime());
            booking.setCanonicalTimezone(canonicalTimezone);
            booking.setBatchId(batch.getId());
            bookingRepository.save(booking);
            sessionDates.add(slot.requestedStartTime());
        }

        String coachEmail = resolveEmail(coach.getUserId());
        String parentName = resolveParentName(parentId);

        eventPublisher.publishEvent(new BatchBookingRequestedEvent(
            this, batch.getId(), coachEmail, parentName,
            req.slots().size(), sessionDates, canonicalTimezone
        ));

        log.info("Batch created: batchId={} parentId={} coachId={} slots={}",
            batch.getId(), parentId, req.coachId(), req.slots().size());

        return new BatchBookingCreatedResponse(batch.getId(), req.slots().size());
    }

    /**
     * Accepts every REQUESTED booking in a batch.
     *
     * <p><strong>This method is deliberately NOT atomic.</strong> Each booking is accepted and
     * committed on its own (Deferred-14 AC3) so that one bad slot cannot fail the whole batch — which
     * is the semantics {@code PARTIALLY_ACCEPTED} always assumed but never actually had. The
     * consequence is a real, accepted residual: a crash between the last per-booking commit and the
     * trailing transaction leaves those bookings in PAYMENT_PENDING with no settlement event, and
     * nothing sweeps that state (tracked in deferred-work.md under the deferred-14 heading). The
     * trailing transaction exists to make that window as small as possible. Do not "simplify" this
     * back into a single transaction, and do not describe it as all-or-nothing.
     */
    @Transactional
    public void acceptAll(UUID batchId, Long coachUserId) {
        BookingBatch batch = batchRepository.findById(batchId)
            .orElseThrow(() -> new ResourceNotFoundException("Booking batch not found", "booking_batch"));

        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));

        if (!batch.getCoachId().equals(coach.getId())) {
            throw new OperationNotAllowedException("Coach does not own this booking batch", SecurityError.MISSING_RIGHTS);
        }

        if (!"PENDING".equals(batch.getStatus())) {
            throw new OperationNotAllowedException("Batch has already been processed", BookingError.BATCH_ALREADY_PROCESSED);
        }

        // Deferred-15 AC4. Deliberately an UNLOCKED check here, with the authoritative locked one in
        // acceptOneBooking: taking the coach lock in this transaction would make every per-booking
        // REQUIRES_NEW transaction below block on a lock its own caller holds, on a different
        // connection, until the 5s timeout. This check is what turns a suspended coach's acceptAll
        // into a clean 403 booking.coachUnavailable — without it the per-booking throws are
        // swallowed by the loop's catch and the caller sees a silent no-op.
        if (coach.getStatus() == CoachProfileStatus.SUSPENDED) {
            throw new OperationNotAllowedException("Coach is suspended",
                Map.of("submitted coach id", coach.getId()), BookingError.COACH_UNAVAILABLE);
        }

        List<Booking> requestedBookings = bookingRepository.findByBatchIdAndStatus(batchId, "REQUESTED");
        List<UUID> acceptedIds = new ArrayList<>();

        for (Booking b : requestedBookings) {
            try {
                // Each booking settles in its own transaction (Deferred-14 AC3). The catch below used
                // to be a lie: acceptAndInitiatePayment is a cross-bean @Transactional call, so a
                // failure inside it marks THIS transaction rollback-only. Swallowing the exception
                // let the loop finish and the batch row be written, and the whole request then died
                // at commit — 500, zero bookings accepted, batch left PENDING. Verified before this
                // fix: a batch whose second slot collides with a CONFIRMED booking returned
                // 500 generic.unknown and accepted nothing. Same defect class Deferred-12 fixed in
                // PaymentLifecycleService; same remedy.
                perBookingTx.executeWithoutResult(tx -> acceptOneBooking(b, coach.getId(), coachUserId));
                acceptedIds.add(b.getId());
            } catch (Exception e) {
                log.warn("Failed to accept booking {} in batch {}: {}", b.getId(), batchId, e.getMessage());
            }
        }

        if (acceptedIds.isEmpty()) {
            log.warn("No bookings were accepted in batch {}", batchId);
            return;
        }

        // Resolved before the trailing transaction opens so that it contains only the batch write and
        // the publish — nothing that can fail on a lookup.
        String parentEmail = resolveEmail(batch.getParentId());
        String parentName = resolveParentName(batch.getParentId());
        String coachEmail = resolveEmail(coach.getUserId());
        Long parentId = batch.getParentId();
        UUID coachId = batch.getCoachId();
        BigDecimal totalAmount = batch.getTotalAmount();

        // Deferred-14 AC3a. The batch status and the settlement event must become durable together,
        // and in their own transaction. With per-booking commits above, leaving this pair in the
        // enclosing transaction meant a failure at ITS commit would strand every booking durably in
        // PAYMENT_PENDING with BatchBookingAcceptedEvent never published — and nothing recovers that:
        // no scheduler reads PAYMENT_PENDING, and the only way out of that status is a
        // parent-initiated CANCEL_PARENT. The stranded rows would also hold the coach's slots via the
        // V87 exclusion constraint. Re-read inside: `batch` above belongs to the enclosing
        // transaction's persistence context.
        //
        // Deferred-15 AC5: the status is now computed from every booking in the batch, re-read
        // inside this transaction — the same formula and the same input the listener uses, so the
        // two writers can no longer disagree. The bookings must be re-read here for the same reason
        // the batch is: `requestedBookings` belongs to the enclosing persistence context and
        // predates the per-booking commits.
        trailingTx.executeWithoutResult(tx ->
            batchRepository.findById(batchId).ifPresent(fresh -> {
                fresh.setStatus(computeBatchStatus(bookingRepository.findByBatchId(batchId)));
                batchRepository.save(fresh);
                eventPublisher.publishEvent(new BatchBookingAcceptedEvent(
                    this, batchId, acceptedIds, parentId, coachId,
                    totalAmount, coachEmail, parentEmail,
                    coach.getDisplayName(), parentName, acceptedIds.size()
                ));
            }));

        log.info("Batch accepted: batchId={} acceptedCount={}", batchId, acceptedIds.size());
    }

    /**
     * Accepts one batch booking inside its own transaction.
     *
     * <p>The lock-then-check pair mirrors {@code BookingService.acceptBooking} (Deferred-14 AC4):
     * the batch path never ran the app-layer overlap check, so a collision could only be caught by
     * the V87 exclusion constraint at commit — which in a batched flush loses the constraint name and
     * surfaces as an unmapped 500 rather than the clean {@code booking.slotUnavailable} the
     * single-booking path returns. Checking here also resolves intra-batch collisions: because each
     * accept commits independently, a slot taken by an earlier booking in this same batch is visible
     * to the later one.
     *
     * <p>excludeBookingId is mandatory — without it a booking already moved to an active status would
     * match itself and mask the real state-transition error.
     */
    private void acceptOneBooking(Booking booking, UUID coachId, Long coachUserId) {
        CoachProfile lockedCoach = coachProfileRepository.findByIdForUpdate(coachId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
        // Deferred-15 AC4. No entityManager.refresh here, unlike the other two accept paths: this
        // method runs inside a REQUIRES_NEW transaction with its own persistence context, so the
        // coach row is not already managed and the locked read genuinely returns fresh state. (On
        // BookingService.acceptBooking and RescheduleService.acceptReschedule the row IS
        // pre-managed from an earlier findByUserId, which is why they must refresh.)
        if (lockedCoach.getStatus() == CoachProfileStatus.SUSPENDED) {
            throw new OperationNotAllowedException("Coach is suspended",
                Map.of("submitted coach id", coachId), BookingError.COACH_UNAVAILABLE);
        }

        List<Booking> overlapping = bookingRepository.findOverlappingBookings(
            coachId, booking.getRequestedStartTime(), booking.getRequestedEndTime(),
            BookingService.ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED, booking.getId());
        if (!overlapping.isEmpty()) {
            throw new OperationNotAllowedException(
                "This slot is no longer available — another booking was accepted for the same time",
                Map.of("submitted coach id", coachId, "requested start time", booking.getRequestedStartTime(),
                    "requested end time", booking.getRequestedEndTime()),
                BookingError.SLOT_UNAVAILABLE);
        }

        // Must be the accept-then-initiate pair, not a bare ACCEPT: PaymentLifecycleService
        // settles these bookings with PAYMENT_CAPTURED/PAYMENT_FAILED, which the state
        // machine only permits from PAYMENT_PENDING (Deferred-12 AC6). INITIATE_PAYMENT is
        // authorised for ActorRole.COACH, so the context below covers both legs.
        bookingService.acceptAndInitiatePayment(booking.getId(), new TransitionContext(ActorRole.COACH, coachUserId));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateBatchStatusFromBooking(UUID batchId) {
        List<Booking> allBookings = bookingRepository.findByBatchId(batchId);
        if (allBookings.isEmpty()) return;

        long requestedCount = allBookings.stream()
            .filter(b -> "REQUESTED".equals(b.getStatus()))
            .count();

        if (requestedCount > 0) {
            return;
        }

        String newStatus = computeBatchStatus(allBookings);

        batchRepository.findById(batchId).ifPresent(batch -> {
            batch.setStatus(newStatus);
            batchRepository.save(batch);
            log.info("Batch status updated: batchId={} newStatus={}", batchId, newStatus);
        });
    }

    /**
     * The single batch-status formula (Deferred-15 AC5). Both writers of {@code booking_batches
     * .status} — this class's trailing transaction in acceptAll and the AFTER_COMMIT
     * {@code BookingBatchStatusListener} via {@link #updateBatchStatusFromBooking} — now compute it
     * from the same input: every booking in the batch.
     *
     * <p>acceptAll used to compare {@code acceptedIds.size()} against the REQUESTED subset captured
     * at loop start, so a batch already containing an individually-declined booking read
     * FULLY_ACCEPTED. Since Deferred-14's per-booking commits the listener fires mid-loop, which made
     * the naive value the one that won.
     */
    private String computeBatchStatus(List<Booking> allInBatch) {
        // Without this, an empty list yields FULLY_ACCEPTED via a vacuous 0 == 0. Not reachable
        // today — updateBatchStatusFromBooking early-returns on empty, and acceptAll only reaches
        // its trailing transaction after accepting at least one booking — but this method is now the
        // single formula both writers share, which makes it the place the degenerate case belongs.
        // PENDING, not FULLY_ACCEPTED: a batch with no bookings has decided nothing.
        if (allInBatch.isEmpty()) return "PENDING";

        long acceptedCount = allInBatch.stream()
            .filter(b -> POST_ACCEPTANCE_STATUSES.contains(b.getStatus()))
            .count();
        if (acceptedCount == allInBatch.size()) return "FULLY_ACCEPTED";
        if (acceptedCount == 0) return "DECLINED";
        return "PARTIALLY_ACCEPTED";
    }

    private String resolveEmail(Long userId) {
        return userRepository.findById(userId).map(u -> u.getEmail()).orElse("");
    }

    private String resolveParentName(Long parentId) {
        return userRepository.findById(parentId)
            .map(u -> u.getFirstName() + " " + u.getLastName())
            .orElse("Unknown Parent");
    }
}
