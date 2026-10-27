package com.softropic.skillars.platform.session.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.booking.contract.BookingCompletedEvent;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.payment.service.PackSessionService;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.service.DevelopmentDataErasureGuard;
import com.softropic.skillars.platform.security.service.PlayerOwnershipGuard;
import com.softropic.skillars.platform.session.contract.DrillResponse;
import com.softropic.skillars.platform.session.contract.HomeworkAssignedEvent;
import com.softropic.skillars.platform.session.contract.HomeworkAssignmentResponse;
import com.softropic.skillars.platform.session.repo.Drill;
import com.softropic.skillars.platform.session.repo.DrillRepository;
import com.softropic.skillars.platform.session.repo.HomeworkAssignment;
import com.softropic.skillars.platform.session.repo.HomeworkAssignmentRepository;
import com.softropic.skillars.platform.session.repo.HomeworkCompletionRepository;
import com.softropic.skillars.platform.session.repo.SessionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class HomeworkAssignmentService {

    private final HomeworkAssignmentRepository homeworkAssignmentRepository;
    private final HomeworkCompletionRepository homeworkCompletionRepository;
    private final SessionRepository sessionRepository;
    private final DrillRepository drillRepository;
    private final DrillLibraryService drillLibraryService;
    private final PackSessionService packSessionService;
    private final CoachProfileService coachProfileService;
    private final PlayerProfileRepository playerProfileRepository;
    private final MeterRegistry meterRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final DevelopmentDataErasureGuard erasureGuard;
    private final PlatformTransactionManager transactionManager;
    private final PlayerOwnershipGuard playerOwnershipGuard;

    private static final String LOCKER_ROOM_DRILLS_LATENCY = "session.homework.locker_room_drills.latency";

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("taskExecutor")
    public void handleBookingCompleted(BookingCompletedEvent event) {
        if (event.getHomeworkDrillIds() == null || event.getHomeworkDrillIds().isEmpty()) {
            return;
        }
        // Story Deferred-75 AC6: WrapUpRequest.homeworkDrillIds carries @Size(max=2) on the HTTP path,
        // but that constraint isn't enforced here — this is a defense-in-depth guard against a future
        // publisher of BookingCompletedEvent assigning an unbounded number of homework drills. Truncate
        // rather than reject: this runs @Async after commit, so there is no caller to return an error to.
        if (event.getHomeworkDrillIds().size() > 2) {
            log.warn("BookingCompletedEvent for booking {} carries {} homeworkDrillIds, exceeding the max of 2 — truncating",
                event.getBookingId(), event.getHomeworkDrillIds().size());
        }
        List<UUID> drillIdsToAssign = event.getHomeworkDrillIds().stream().limit(2).toList();

        UUID sessionId = sessionRepository.findByBookingId(event.getBookingId())
            .map(s -> s.getId()).orElse(null);
        if (sessionId == null) {
            log.debug("No session found for booking {} — assigning homework with null sessionId (expected for QUICK-mode bookings)", event.getBookingId());
        }
        UUID packId = resolvePackId(event.getPlayerId(), event.getCoachId());
        // skillars-12-8 AC6: only rows actually inserted by THIS invocation are announced, so a replayed
        // BookingCompletedEvent publishes nothing. Published from a finally so a non-duplicate-key failure
        // on drill 2 cannot swallow the already-committed drill 1 (a replay would skip it as "exists").
        //
        // skillars-deferred-162 AC5: each drill is its own REQUIRES_NEW transaction that locks the player
        // profile, checks the erasure marker and inserts, then commits. One transaction for the whole loop
        // would let drill 2's duplicate-key failure mark it rollback-only and silently discard drill 1, so the
        // catch stays outside each per-drill transaction. An erased player stops the loop and, with nothing
        // newly assigned, no HomeworkAssignedEvent is published.
        TransactionTemplate perDrillTx = new TransactionTemplate(transactionManager);
        perDrillTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        List<UUID> newlyAssigned = new ArrayList<>();
        try {
            for (UUID drillId : drillIdsToAssign) {
                DrillOutcome outcome;
                try {
                    outcome = perDrillTx.execute(status -> assignOne(event, drillId, sessionId, packId));
                } catch (DataIntegrityViolationException e) {
                    // UNIQUE(booking_id, drill_id) constraint violation — race condition on concurrent event replay
                    log.warn("Duplicate homework assignment for booking {} drill {} — ignored", event.getBookingId(), drillId);
                    continue;
                }
                if (outcome == DrillOutcome.PLAYER_ERASED) {
                    break;
                }
                if (outcome == DrillOutcome.ASSIGNED) {
                    newlyAssigned.add(drillId);
                }
            }
        } finally {
            if (!newlyAssigned.isEmpty()) {
                // No active transaction here (each per-drill transaction has already committed); the timeline
                // consumer therefore declares fallbackExecution = true.
                eventPublisher.publishEvent(new HomeworkAssignedEvent(
                    event.getBookingId(), event.getPlayerId(), event.getCoachId(), List.copyOf(newlyAssigned)));
            }
        }
    }

    private enum DrillOutcome { ASSIGNED, ALREADY_ASSIGNED, PLAYER_ERASED }

    private DrillOutcome assignOne(BookingCompletedEvent event, UUID drillId, UUID sessionId, UUID packId) {
        if (erasureGuard.isErasedUnderLock(event.getPlayerId(), "HomeworkAssignmentService.handleBookingCompleted")) {
            return DrillOutcome.PLAYER_ERASED;
        }
        // Idempotency: skip if assignment already exists for this booking+drill.
        // Uses bookingId (never null) — not sessionId, which may be null for QUICK-mode bookings.
        // PostgreSQL UNIQUE constraints treat NULL ≠ NULL, so a nullable-column constraint gives
        // zero protection when that column is null; bookingId is the correct idempotency anchor.
        if (homeworkAssignmentRepository.existsByBookingIdAndDrillId(event.getBookingId(), drillId)) {
            log.debug("Homework assignment already exists for booking {} drill {} — skipping", event.getBookingId(), drillId);
            return DrillOutcome.ALREADY_ASSIGNED;
        }
        HomeworkAssignment assignment = new HomeworkAssignment();
        assignment.setBookingId(event.getBookingId());
        assignment.setSessionId(sessionId);
        assignment.setPlayerId(event.getPlayerId());
        assignment.setCoachId(event.getCoachId());
        assignment.setDrillId(drillId);
        assignment.setPackId(packId);
        homeworkAssignmentRepository.saveAndFlush(assignment);
        return DrillOutcome.ASSIGNED;
    }

    @Transactional(readOnly = true)
    public List<HomeworkAssignmentResponse> getLockerRoomDrills(Long playerId) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            List<HomeworkAssignment> all = homeworkAssignmentRepository.findByPlayerIdOrderByAssignedAtDesc(playerId);
            if (all.isEmpty()) {
                success = true;
                return List.of();
            }

            Set<UUID> allCoachIds = all.stream().map(HomeworkAssignment::getCoachId).collect(Collectors.toSet());
            Set<UUID> activeCoachIds = packSessionService.hasActivePackForAnyOf(playerId, allCoachIds);

            List<HomeworkAssignment> active = all.stream()
                .filter(a -> activeCoachIds.contains(a.getCoachId()))
                .collect(Collectors.toList());

            if (active.isEmpty()) {
                success = true;
                return List.of();
            }

            Set<UUID> drillIds = active.stream().map(HomeworkAssignment::getDrillId).collect(Collectors.toSet());
            Map<UUID, Drill> drillMap = drillRepository.findAllById(drillIds).stream()
                .collect(Collectors.toMap(Drill::getId, d -> d));

            Set<UUID> coachIds = active.stream().map(HomeworkAssignment::getCoachId).collect(Collectors.toSet());
            Map<UUID, String> coachNameMap = coachProfileService.getDisplayNamesByIds(coachIds);

            Set<UUID> assignmentIds = active.stream().map(HomeworkAssignment::getId).collect(Collectors.toSet());
            Set<UUID> completedIds = assignmentIds.isEmpty() ? Set.of()
                : homeworkCompletionRepository.findAssignmentIdsByPlayerIdAndAssignmentIdIn(playerId, assignmentIds);

            List<HomeworkAssignmentResponse> result = active.stream().map(a -> {
                Drill drill = drillMap.get(a.getDrillId());
                if (drill == null) return null;
                DrillResponse drillResponse = drillLibraryService.toResponse(drill, false, List.of(), null, null, null);
                String coachName = coachNameMap.getOrDefault(a.getCoachId(), "Coach");
                return new HomeworkAssignmentResponse(
                    a.getId(), a.getDrillId(), drillResponse,
                    a.getCoachId(), coachName, a.getAssignedAt(),
                    completedIds.contains(a.getId())
                );
            }).filter(Objects::nonNull).collect(Collectors.toList());
            success = true;
            return result;
        } finally {
            Timer.builder(LOCKER_ROOM_DRILLS_LATENCY)
                .tag("status", success ? "success" : "error")
                .register(meterRegistry)
                .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * Marks a homework assignment complete on behalf of {@code callerUserId} — the authenticated
     * user's id (Principal.businessId), which is either the parent of the assignment's player
     * profile or, for a self-registered adult player, the profile's own {@code user_id}. Anyone
     * else gets a 404 (not a 403) so assignment ids cannot be probed.
     *
     * <p>Idempotent and race-safe: the completion row is written with
     * {@code INSERT ... ON CONFLICT (assignment_id) DO NOTHING}, so concurrent double-taps neither
     * create a second row nor poison the transaction with a caught constraint violation (a caught
     * flush failure used to leave the JPA transaction rollback-only → 500 on commit).
     */
    @Transactional
    public void markComplete(UUID assignmentId, Long callerUserId) {
        HomeworkAssignment assignment = homeworkAssignmentRepository.findById(assignmentId)
            .orElseThrow(() -> new ResourceNotFoundException("Assignment not found", "homework_assignment"));
        Long playerId = assignment.getPlayerId();
        if (!playerOwnershipGuard.owns(callerUserId, playerId)) {
            throw new ResourceNotFoundException("Assignment not found", "homework_assignment");
        }
        homeworkCompletionRepository.insertIfAbsent(UUID.randomUUID(), assignmentId, playerId);
    }

    private UUID resolvePackId(Long playerId, UUID coachId) {
        return packSessionService.getActivePackId(playerId, coachId);
    }
}
