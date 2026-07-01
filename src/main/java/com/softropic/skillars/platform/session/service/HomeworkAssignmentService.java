package com.softropic.skillars.platform.session.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.booking.contract.BookingCompletedEvent;
import com.softropic.skillars.platform.booking.service.SessionPackService;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.session.contract.DrillResponse;
import com.softropic.skillars.platform.session.contract.HomeworkAssignmentResponse;
import com.softropic.skillars.platform.session.repo.Drill;
import com.softropic.skillars.platform.session.repo.DrillRepository;
import com.softropic.skillars.platform.session.repo.HomeworkAssignment;
import com.softropic.skillars.platform.session.repo.HomeworkAssignmentRepository;
import com.softropic.skillars.platform.session.repo.HomeworkCompletion;
import com.softropic.skillars.platform.session.repo.HomeworkCompletionRepository;
import com.softropic.skillars.platform.session.repo.SessionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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
    private final SessionPackService sessionPackService;
    private final CoachProfileService coachProfileService;
    private final PlayerProfileRepository playerProfileRepository;
    private final MeterRegistry meterRegistry;

    private static final String LOCKER_ROOM_DRILLS_LATENCY = "session.homework.locker_room_drills.latency";

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void handleBookingCompleted(BookingCompletedEvent event) {
        if (event.getHomeworkDrillIds() == null || event.getHomeworkDrillIds().isEmpty()) {
            return;
        }
        UUID sessionId = sessionRepository.findByBookingId(event.getBookingId())
            .map(s -> s.getId()).orElse(null);
        UUID packId = resolvePackId(event.getPlayerId(), event.getCoachId());
        for (UUID drillId : event.getHomeworkDrillIds()) {
            // Idempotency: skip if assignment already exists for this booking+drill.
            // Uses bookingId (never null) — not sessionId, which may be null for QUICK-mode bookings.
            // PostgreSQL UNIQUE constraints treat NULL ≠ NULL, so a nullable-column constraint gives
            // zero protection when that column is null; bookingId is the correct idempotency anchor.
            if (homeworkAssignmentRepository.existsByBookingIdAndDrillId(event.getBookingId(), drillId)) {
                log.debug("Homework assignment already exists for booking {} drill {} — skipping", event.getBookingId(), drillId);
                continue;
            }
            HomeworkAssignment assignment = new HomeworkAssignment();
            assignment.setBookingId(event.getBookingId());
            assignment.setSessionId(sessionId);
            assignment.setPlayerId(event.getPlayerId());
            assignment.setCoachId(event.getCoachId());
            assignment.setDrillId(drillId);
            assignment.setPackId(packId);
            try {
                homeworkAssignmentRepository.saveAndFlush(assignment);
            } catch (DataIntegrityViolationException e) {
                // UNIQUE(booking_id, drill_id) constraint violation — race condition on concurrent event replay
                log.warn("Duplicate homework assignment for booking {} drill {} — ignored", event.getBookingId(), drillId);
            }
        }
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

            Set<UUID> activeCoachIds = all.stream()
                .map(HomeworkAssignment::getCoachId)
                .collect(Collectors.toSet())
                .stream()
                .filter(coachId -> sessionPackService.hasActivePack(playerId, coachId))
                .collect(Collectors.toSet());

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

    @Transactional
    public void markComplete(UUID assignmentId, Long parentId) {
        HomeworkAssignment assignment = homeworkAssignmentRepository.findById(assignmentId)
            .orElseThrow(() -> new ResourceNotFoundException("Assignment not found", "homework_assignment"));
        playerProfileRepository.findByIdAndParentId(assignment.getPlayerId(), parentId)
            .orElseThrow(() -> new ResourceNotFoundException("Assignment not found", "homework_assignment"));
        if (homeworkCompletionRepository.existsByAssignmentId(assignmentId)) {
            return;
        }
        HomeworkCompletion completion = new HomeworkCompletion();
        completion.setAssignmentId(assignmentId);
        completion.setPlayerId(assignment.getPlayerId());
        try {
            homeworkCompletionRepository.saveAndFlush(completion);
        } catch (DataIntegrityViolationException e) {
            // UNIQUE(assignment_id) race condition — idempotent, ignore
        }
    }

    private UUID resolvePackId(Long playerId, UUID coachId) {
        return sessionPackService.getActivePackId(playerId, coachId);
    }
}
