package com.softropic.skillars.platform.session.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.development.service.CoachPlayerAuthorizationService;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.session.contract.CoachHomeworkAssignmentResponse;
import com.softropic.skillars.platform.session.repo.Drill;
import com.softropic.skillars.platform.session.repo.DrillRepository;
import com.softropic.skillars.platform.session.repo.HomeworkAssignment;
import com.softropic.skillars.platform.session.repo.HomeworkAssignmentRepository;
import com.softropic.skillars.platform.session.repo.HomeworkCompletion;
import com.softropic.skillars.platform.session.repo.HomeworkCompletionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Coach-side homework completion view (CoachPlayerDetailPage). Read-only.
 *
 * <p>Authorization is the shared coach-access window for per-player reads (skillars-12-10,
 * {@link CoachPlayerAuthorizationService#requireCoachPlayerRelationshipForRead}) — the same rule every
 * other CoachPlayerDetailPage section enforces. Results are siloed to the homework THIS coach assigned:
 * another coach's assignments to the same player are not this coach's to see.
 */
@Service
@RequiredArgsConstructor
public class CoachHomeworkQueryService {

    private final HomeworkAssignmentRepository homeworkAssignmentRepository;
    private final HomeworkCompletionRepository homeworkCompletionRepository;
    private final DrillRepository drillRepository;
    private final CoachProfileService coachProfileService;
    private final CoachPlayerAuthorizationService coachPlayerAuthorizationService;

    @Transactional(readOnly = true)
    public List<CoachHomeworkAssignmentResponse> listForCoach(Long coachUserId, Long playerId) {
        UUID coachId;
        try {
            coachId = coachProfileService.getCoachIdByUserId(coachUserId);
        } catch (ResourceNotFoundException e) {
            throw new OperationNotAllowedException("Coach profile not found", SecurityError.MISSING_RIGHTS);
        }
        coachPlayerAuthorizationService.requireCoachPlayerRelationshipForRead(coachId, playerId);

        List<HomeworkAssignment> assignments =
            homeworkAssignmentRepository.findByPlayerIdAndCoachIdOrderByAssignedAtDesc(playerId, coachId);
        if (assignments.isEmpty()) {
            return List.of();
        }

        Set<UUID> assignmentIds = assignments.stream().map(HomeworkAssignment::getId).collect(Collectors.toSet());
        Map<UUID, Instant> completedAtByAssignment = homeworkCompletionRepository.findByAssignmentIdIn(assignmentIds)
            .stream()
            .collect(Collectors.toMap(HomeworkCompletion::getAssignmentId, HomeworkCompletion::getCompletedAt,
                (a, b) -> a));

        Set<UUID> drillIds = assignments.stream().map(HomeworkAssignment::getDrillId).collect(Collectors.toSet());
        Map<UUID, Drill> drills = drillRepository.findAllById(drillIds).stream()
            .collect(Collectors.toMap(Drill::getId, Function.identity()));

        return assignments.stream()
            .map(a -> {
                Drill drill = drills.get(a.getDrillId());
                Instant completedAt = completedAtByAssignment.get(a.getId());
                return new CoachHomeworkAssignmentResponse(
                    a.getId(),
                    a.getDrillId(),
                    drill != null ? drill.getName() : null,
                    a.getBookingId(),
                    a.getAssignedAt(),
                    completedAt != null,
                    completedAt);
            })
            .toList();
    }
}
