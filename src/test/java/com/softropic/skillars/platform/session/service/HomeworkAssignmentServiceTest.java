package com.softropic.skillars.platform.session.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.booking.contract.BookingCompletedEvent;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.payment.service.PackSessionService;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.session.contract.HomeworkAssignmentResponse;
import com.softropic.skillars.platform.session.repo.Drill;
import com.softropic.skillars.platform.session.repo.DrillRepository;
import com.softropic.skillars.platform.session.repo.HomeworkAssignment;
import com.softropic.skillars.platform.session.repo.HomeworkAssignmentRepository;
import com.softropic.skillars.platform.session.repo.HomeworkCompletionRepository;
import com.softropic.skillars.platform.session.repo.SessionRepository;

import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HomeworkAssignmentServiceTest {

    @Mock private HomeworkAssignmentRepository homeworkAssignmentRepository;
    @Mock private HomeworkCompletionRepository homeworkCompletionRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private DrillRepository drillRepository;
    @Mock private DrillLibraryService drillLibraryService;
    @Mock private PackSessionService packSessionService;
    @Mock private CoachProfileService coachProfileService;
    @Mock private PlayerProfileRepository playerProfileRepository;
    @Spy private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks private HomeworkAssignmentService service;

    @Test
    void handleBookingCompleted_withDrills_createsAssignments() {
        UUID bookingId = UUID.randomUUID();
        UUID coachId = UUID.randomUUID();
        Long playerId = 1001L;
        UUID drillId1 = UUID.randomUUID();
        UUID drillId2 = UUID.randomUUID();

        BookingCompletedEvent event = new BookingCompletedEvent(this, bookingId, coachId, playerId,
            2001L, true, 4, 4, 4, List.of(drillId1, drillId2));

        when(sessionRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());
        when(packSessionService.getActivePackId(playerId, coachId)).thenReturn(null);
        when(homeworkAssignmentRepository.existsByBookingIdAndDrillId(any(), any())).thenReturn(false);

        service.handleBookingCompleted(event);

        verify(homeworkAssignmentRepository, times(2)).saveAndFlush(any(HomeworkAssignment.class));
    }

    @Test
    void handleBookingCompleted_emptyDrills_doesNothing() {
        BookingCompletedEvent event = new BookingCompletedEvent(this, UUID.randomUUID(), UUID.randomUUID(),
            1001L, 2001L, true, null, null, null, List.of());

        service.handleBookingCompleted(event);

        verify(homeworkAssignmentRepository, never()).save(any());
    }

    @Test
    void handleBookingCompleted_nullDrills_doesNothing() {
        BookingCompletedEvent event = new BookingCompletedEvent(this, UUID.randomUUID(), UUID.randomUUID(),
            1001L, 2001L, true, null, null, null, null);

        service.handleBookingCompleted(event);

        verify(homeworkAssignmentRepository, never()).save(any());
    }

    @Test
    void handleBookingCompleted_duplicateEvent_skipsExisting() {
        UUID bookingId = UUID.randomUUID();
        UUID drillId = UUID.randomUUID();
        BookingCompletedEvent event = new BookingCompletedEvent(this, bookingId, UUID.randomUUID(),
            1001L, 2001L, true, null, null, null, List.of(drillId));

        when(sessionRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());
        when(packSessionService.getActivePackId(anyLong(), any())).thenReturn(null);
        when(homeworkAssignmentRepository.existsByBookingIdAndDrillId(bookingId, drillId)).thenReturn(true);

        service.handleBookingCompleted(event);

        verify(homeworkAssignmentRepository, never()).save(any());
    }

    @Test
    void handleBookingCompleted_moreThanTwoHomeworkDrillIds_assignsOnlyFirstTwo() {
        UUID bookingId = UUID.randomUUID();
        UUID coachId = UUID.randomUUID();
        Long playerId = 1001L;
        UUID drillId1 = UUID.randomUUID();
        UUID drillId2 = UUID.randomUUID();
        UUID drillId3 = UUID.randomUUID();

        BookingCompletedEvent event = new BookingCompletedEvent(this, bookingId, coachId, playerId,
            2001L, true, 4, 4, 4, List.of(drillId1, drillId2, drillId3));

        when(sessionRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());
        when(packSessionService.getActivePackId(playerId, coachId)).thenReturn(null);
        when(homeworkAssignmentRepository.existsByBookingIdAndDrillId(any(), any())).thenReturn(false);

        service.handleBookingCompleted(event);

        verify(homeworkAssignmentRepository, times(2)).saveAndFlush(any(HomeworkAssignment.class));
        verify(homeworkAssignmentRepository, never()).existsByBookingIdAndDrillId(bookingId, drillId3);
    }

    @Test
    void getLockerRoomDrills_activePackFilter_excludesExhaustedCoach() {
        Long playerId = 1001L;
        UUID activeCoachId = UUID.randomUUID();
        UUID exhaustedCoachId = UUID.randomUUID();
        UUID drillId = UUID.randomUUID();

        HomeworkAssignment activeAssignment = buildAssignment(playerId, activeCoachId, drillId);
        HomeworkAssignment exhaustedAssignment = buildAssignment(playerId, exhaustedCoachId, UUID.randomUUID());

        when(homeworkAssignmentRepository.findByPlayerIdOrderByAssignedAtDesc(playerId))
            .thenReturn(List.of(activeAssignment, exhaustedAssignment));
        when(packSessionService.hasActivePackForAnyOf(eq(playerId), any()))
            .thenReturn(Set.of(activeCoachId));

        Drill drill = buildDrill(drillId);
        when(drillRepository.findAllById(any())).thenReturn(List.of(drill));
        when(coachProfileService.getDisplayNamesByIds(any())).thenReturn(Map.of(activeCoachId, "Coach A"));
        when(homeworkCompletionRepository.findAssignmentIdsByPlayerIdAndAssignmentIdIn(anyLong(), any()))
            .thenReturn(Set.of());
        com.softropic.skillars.platform.session.contract.DrillResponse drillResponse =
            Instancio.create(com.softropic.skillars.platform.session.contract.DrillResponse.class);
        when(drillLibraryService.toResponse(any(), any(Boolean.class), any(), any(), any(), any()))
            .thenReturn(drillResponse);

        List<HomeworkAssignmentResponse> result = service.getLockerRoomDrills(playerId);

        // Exhausted coach's assignment is excluded; only active coach's drill remains
        assertThat(result).hasSize(1);
        assertThat(result.get(0).coachId()).isEqualTo(activeCoachId);
    }

    @Test
    void getLockerRoomDrills_manyDistinctCoaches_looksUpActivePacksInOneBatchCall() {
        // Story Deferred-75 AC6: proves the N+1 fix — regardless of how many distinct coaches appear
        // across the player's assignments, hasActivePackForAnyOf must be called exactly once, not
        // once per coach.
        Long playerId = 1001L;
        List<HomeworkAssignment> assignments = new java.util.ArrayList<>();
        Set<UUID> coachIds = new java.util.HashSet<>();
        for (int i = 0; i < 5; i++) {
            UUID coachId = UUID.randomUUID();
            coachIds.add(coachId);
            assignments.add(buildAssignment(playerId, coachId, UUID.randomUUID()));
        }

        when(homeworkAssignmentRepository.findByPlayerIdOrderByAssignedAtDesc(playerId))
            .thenReturn(assignments);
        when(packSessionService.hasActivePackForAnyOf(eq(playerId), any())).thenReturn(coachIds);
        when(drillRepository.findAllById(any())).thenReturn(List.of());
        when(coachProfileService.getDisplayNamesByIds(any())).thenReturn(Map.of());
        when(homeworkCompletionRepository.findAssignmentIdsByPlayerIdAndAssignmentIdIn(anyLong(), any()))
            .thenReturn(Set.of());

        service.getLockerRoomDrills(playerId);

        verify(packSessionService, times(1)).hasActivePackForAnyOf(eq(playerId), eq(coachIds));
    }

    @Test
    void getLockerRoomDrills_emptyResult_returnsEmptyList() {
        when(homeworkAssignmentRepository.findByPlayerIdOrderByAssignedAtDesc(1001L)).thenReturn(List.of());

        List<HomeworkAssignmentResponse> result = service.getLockerRoomDrills(1001L);

        assertThat(result).isEmpty();
    }

    @Test
    void getLockerRoomDrills_completedDrills_flaggedCorrectly() {
        Long playerId = 1001L;
        UUID coachId = UUID.randomUUID();
        UUID drillId = UUID.randomUUID();

        HomeworkAssignment assignment = buildAssignment(playerId, coachId, drillId);
        UUID assignmentId = assignment.getId();

        when(homeworkAssignmentRepository.findByPlayerIdOrderByAssignedAtDesc(playerId))
            .thenReturn(List.of(assignment));
        when(packSessionService.hasActivePackForAnyOf(eq(playerId), any())).thenReturn(Set.of(coachId));

        Drill drill = buildDrill(drillId);
        when(drillRepository.findAllById(any())).thenReturn(List.of(drill));
        when(coachProfileService.getDisplayNamesByIds(any())).thenReturn(Map.of(coachId, "Coach A"));
        when(homeworkCompletionRepository.findAssignmentIdsByPlayerIdAndAssignmentIdIn(anyLong(), any()))
            .thenReturn(Set.of(assignmentId));

        com.softropic.skillars.platform.session.contract.DrillResponse drillResponse =
            Instancio.create(com.softropic.skillars.platform.session.contract.DrillResponse.class);
        when(drillLibraryService.toResponse(any(), any(Boolean.class), any(), any(), any(), any()))
            .thenReturn(drillResponse);

        List<HomeworkAssignmentResponse> result = service.getLockerRoomDrills(playerId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).completed()).isTrue();
    }

    @Test
    void markComplete_idempotent_doesNotThrowOnDuplicate() {
        UUID assignmentId = UUID.randomUUID();
        Long parentId = 2001L;
        HomeworkAssignment assignment = buildAssignment(1001L, UUID.randomUUID(), UUID.randomUUID());

        when(homeworkAssignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(playerProfileRepository.findByIdAndParentId(assignment.getPlayerId(), parentId))
            .thenReturn(Optional.of(new PlayerProfile()));
        when(homeworkCompletionRepository.existsByAssignmentId(assignmentId)).thenReturn(true);

        service.markComplete(assignmentId, parentId);

        verify(homeworkCompletionRepository, never()).save(any());
    }

    @Test
    void markComplete_wrongParent_throws404() {
        UUID assignmentId = UUID.randomUUID();
        Long parentId = 9999L;
        HomeworkAssignment assignment = buildAssignment(1001L, UUID.randomUUID(), UUID.randomUUID());

        when(homeworkAssignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(playerProfileRepository.findByIdAndParentId(assignment.getPlayerId(), parentId))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markComplete(assignmentId, parentId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    private HomeworkAssignment buildAssignment(Long playerId, UUID coachId, UUID drillId) {
        HomeworkAssignment a = new HomeworkAssignment();
        a.setId(UUID.randomUUID());
        a.setBookingId(UUID.randomUUID());
        a.setPlayerId(playerId);
        a.setCoachId(coachId);
        a.setDrillId(drillId);
        return a;
    }

    private Drill buildDrill(UUID id) {
        return Instancio.of(Drill.class)
            .set(field(Drill::getId), id)
            .create();
    }
}
