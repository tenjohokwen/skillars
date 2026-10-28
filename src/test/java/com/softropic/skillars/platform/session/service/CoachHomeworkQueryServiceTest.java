package com.softropic.skillars.platform.session.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** UI-gap chunk C7 / D2: coach-scoped homework completion view. */
@ExtendWith(MockitoExtension.class)
class CoachHomeworkQueryServiceTest {

    private static final long COACH_USER_ID = 77L;
    private static final long PLAYER_ID = 893_573_203_704_173_564L;

    @Mock HomeworkAssignmentRepository homeworkAssignmentRepository;
    @Mock HomeworkCompletionRepository homeworkCompletionRepository;
    @Mock DrillRepository drillRepository;
    @Mock CoachProfileService coachProfileService;
    @Mock CoachPlayerAuthorizationService coachPlayerAuthorizationService;

    @InjectMocks CoachHomeworkQueryService service;

    private static HomeworkAssignment assignment(UUID coachId, UUID drillId, Instant assignedAt) {
        HomeworkAssignment a = new HomeworkAssignment();
        a.setId(UUID.randomUUID());
        a.setCoachId(coachId);
        a.setPlayerId(PLAYER_ID);
        a.setDrillId(drillId);
        a.setBookingId(UUID.randomUUID());
        a.setAssignedAt(assignedAt);
        return a;
    }

    @Test
    void coachWithoutProfile_isForbidden_andReadsNothing() {
        when(coachProfileService.getCoachIdByUserId(COACH_USER_ID))
            .thenThrow(new ResourceNotFoundException("Coach profile not found", "CoachProfile"));

        assertThatThrownBy(() -> service.listForCoach(COACH_USER_ID, PLAYER_ID))
            .isInstanceOf(OperationNotAllowedException.class);
        verifyNoInteractions(coachPlayerAuthorizationService, homeworkAssignmentRepository);
    }

    @Test
    void closedAccessWindow_propagates_andReadsNoHomework() {
        UUID coachId = UUID.randomUUID();
        when(coachProfileService.getCoachIdByUserId(COACH_USER_ID)).thenReturn(coachId);
        doThrow(new OperationNotAllowedException("no relationship",
                com.softropic.skillars.infrastructure.security.SecurityError.MISSING_RIGHTS))
            .when(coachPlayerAuthorizationService).requireCoachPlayerRelationshipForRead(coachId, PLAYER_ID);

        assertThatThrownBy(() -> service.listForCoach(COACH_USER_ID, PLAYER_ID))
            .isInstanceOf(OperationNotAllowedException.class);
        verify(homeworkAssignmentRepository, never()).findByPlayerIdAndCoachIdOrderByAssignedAtDesc(anyLong(), any());
    }

    @Test
    void returnsOnlyThisCoachsAssignments_withCompletionAndDrillName() {
        UUID coachId = UUID.randomUUID();
        UUID drillId = UUID.randomUUID();
        when(coachProfileService.getCoachIdByUserId(COACH_USER_ID)).thenReturn(coachId);
        HomeworkAssignment done = assignment(coachId, drillId, Instant.parse("2026-10-05T10:00:00Z"));
        HomeworkAssignment open = assignment(coachId, UUID.randomUUID(), Instant.parse("2026-10-01T10:00:00Z"));
        when(homeworkAssignmentRepository.findByPlayerIdAndCoachIdOrderByAssignedAtDesc(PLAYER_ID, coachId))
            .thenReturn(List.of(done, open));
        HomeworkCompletion completion = new HomeworkCompletion();
        completion.setAssignmentId(done.getId());
        completion.setCompletedAt(Instant.parse("2026-10-06T10:00:00Z"));
        when(homeworkCompletionRepository.findByAssignmentIdIn(any())).thenReturn(List.of(completion));
        Drill drill = new Drill();
        drill.setId(drillId);
        drill.setName("Rondo");
        when(drillRepository.findAllById(any())).thenReturn(List.of(drill));

        List<CoachHomeworkAssignmentResponse> result = service.listForCoach(COACH_USER_ID, PLAYER_ID);

        // Authorization is checked before any homework row is read.
        InOrder order = inOrder(coachPlayerAuthorizationService, homeworkAssignmentRepository);
        order.verify(coachPlayerAuthorizationService).requireCoachPlayerRelationshipForRead(coachId, PLAYER_ID);
        order.verify(homeworkAssignmentRepository).findByPlayerIdAndCoachIdOrderByAssignedAtDesc(PLAYER_ID, coachId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).assignmentId()).isEqualTo(done.getId());
        assertThat(result.get(0).drillName()).isEqualTo("Rondo");
        assertThat(result.get(0).completed()).isTrue();
        assertThat(result.get(0).completedAt()).isEqualTo(Instant.parse("2026-10-06T10:00:00Z"));
        assertThat(result.get(1).drillName()).isNull();
        assertThat(result.get(1).completed()).isFalse();
        assertThat(result.get(1).completedAt()).isNull();
    }
}
