package com.softropic.skillars.platform.development.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.booking.contract.BookingCompletedEvent;
import com.softropic.skillars.platform.booking.repo.SessionCompletionDataRepository;
import com.softropic.skillars.platform.development.contract.PlayerTimelineEventType;
import com.softropic.skillars.platform.development.repo.PlayerTimelineEvent;
import com.softropic.skillars.platform.development.repo.PlayerTimelineRepository;
import com.softropic.skillars.platform.session.contract.HomeworkAssignedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** skillars-12-8 AC1/AC2/AC7 at unit level. The async/REQUIRES_NEW wiring is covered by the ITs. */
@ExtendWith(MockitoExtension.class)
class TimelineEventListenerTest {

    @Mock private PlayerTimelineRepository timelineRepository;
    @Mock private SessionCompletionDataRepository completionDataRepository;
    @Mock private TimelineDrillSnapshotResolver drillSnapshotResolver;

    private TimelineEventListener listener;
    private final UUID bookingId = UUID.randomUUID();
    private final UUID coachId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new TimelineEventListener(timelineRepository, completionDataRepository,
            new ObjectMapper(), drillSnapshotResolver);
        ReflectionTestUtils.setField(listener, "self", listener);
    }

    private BookingCompletedEvent completed(boolean attended, Integer effort, Integer focus, Integer technique) {
        return new BookingCompletedEvent(this, bookingId, coachId, 7L, 9L, attended, effort, focus, technique, List.of());
    }

    private PlayerTimelineEvent savedEvent() {
        ArgumentCaptor<PlayerTimelineEvent> c = ArgumentCaptor.forClass(PlayerTimelineEvent.class);
        verify(timelineRepository).save(c.capture());
        return c.getValue();
    }

    @Test
    void attended_writesDrillsAndNonNullRatings_withBookingReference() {
        when(completionDataRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());
        List<Map<String, Object>> drills = List.of(Map.of("drillId", UUID.randomUUID().toString(), "name", "Rondo"));
        when(drillSnapshotResolver.forBooking(bookingId)).thenReturn(drills);

        listener.onBookingCompleted(completed(true, 4, null, 5));

        PlayerTimelineEvent e = savedEvent();
        assertThat(e.getReferenceId()).isEqualTo(bookingId);
        assertThat(e.getReferenceModule()).isEqualTo("booking");
        assertThat(e.getMetadata())
            .containsEntry(TimelineEventListener.METADATA_KEY_DRILLS, drills)
            .containsEntry(TimelineEventListener.METADATA_KEY_EFFORT_RATING, 4)
            .containsEntry(TimelineEventListener.METADATA_KEY_TECHNIQUE_RATING, 5)
            .doesNotContainKey(TimelineEventListener.METADATA_KEY_FOCUS_RATING);
        assertThat(e.getCoachId()).isEqualTo(coachId);
    }

    @Test
    void attended_noDrills_omitsDrillsKey() {
        when(completionDataRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());
        when(drillSnapshotResolver.forBooking(bookingId)).thenReturn(List.of());

        listener.onBookingCompleted(completed(true, null, null, null));

        assertThat(savedEvent().getMetadata()).doesNotContainKeys(
            TimelineEventListener.METADATA_KEY_DRILLS, TimelineEventListener.METADATA_KEY_EFFORT_RATING);
    }

    @Test
    void unattended_hasNoDrillsNoRatings_andNoAccessClockCoach() {
        when(completionDataRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());

        listener.onBookingCompleted(completed(false, 3, 3, 3));

        PlayerTimelineEvent e = savedEvent();
        assertThat(e.getMetadata()).doesNotContainKeys(
            TimelineEventListener.METADATA_KEY_DRILLS, TimelineEventListener.METADATA_KEY_EFFORT_RATING,
            TimelineEventListener.METADATA_KEY_FOCUS_RATING, TimelineEventListener.METADATA_KEY_TECHNIQUE_RATING);
        assertThat(e.getCoachId()).isNull();
        verify(drillSnapshotResolver, never()).forBooking(any());
    }

    @Test
    void homeworkAssigned_writesOnce_withNullCoachColumn_andCoachIdInMetadata() {
        UUID d1 = UUID.randomUUID();
        List<Map<String, Object>> drills = List.of(Map.of("drillId", d1.toString(), "name", "Passing"));
        when(timelineRepository.existsByPlayerIdAndEventTypeAndReferenceId(
            7L, PlayerTimelineEventType.HOMEWORK_ASSIGNED, bookingId)).thenReturn(false);
        when(drillSnapshotResolver.snapshot(List.of(d1))).thenReturn(drills);

        listener.onHomeworkAssigned(new HomeworkAssignedEvent(bookingId, 7L, coachId, List.of(d1)));

        PlayerTimelineEvent e = savedEvent();
        assertThat(e.getEventType()).isEqualTo(PlayerTimelineEventType.HOMEWORK_ASSIGNED);
        assertThat(e.getReferenceId()).isEqualTo(bookingId);
        assertThat(e.getCoachId()).isNull();
        assertThat(e.getMetadata()).containsEntry(TimelineEventListener.METADATA_KEY_DRILLS, drills)
            .containsEntry(TimelineEventListener.METADATA_KEY_COACH_ID, coachId.toString());
    }

    @Test
    void homeworkAssigned_existingRow_mergesIntoIt_neverASecondRow() {
        UUID d2 = UUID.randomUUID();
        List<Map<String, Object>> drills = List.of(Map.of("drillId", d2.toString(), "name", "Dribbling"));
        when(drillSnapshotResolver.snapshot(List.of(d2))).thenReturn(drills);
        when(timelineRepository.existsByPlayerIdAndEventTypeAndReferenceId(
            eq(7L), eq(PlayerTimelineEventType.HOMEWORK_ASSIGNED), eq(bookingId))).thenReturn(true);

        listener.onHomeworkAssigned(new HomeworkAssignedEvent(bookingId, 7L, coachId, List.of(d2)));

        verify(timelineRepository, never()).save(any());
        verify(timelineRepository).appendHomeworkDrills(eq(7L), eq(bookingId), contains(d2.toString()));
    }

    @Test
    void attended_resolverThrows_sessionCompletedStillWritten() {
        when(completionDataRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());
        when(drillSnapshotResolver.forBooking(bookingId)).thenThrow(new IllegalStateException("boom"));

        listener.onBookingCompleted(completed(true, 4, null, null));

        PlayerTimelineEvent e = savedEvent();
        assertThat(e.getEventType()).isEqualTo(PlayerTimelineEventType.SESSION_COMPLETED);
        assertThat(e.getMetadata()).doesNotContainKey(TimelineEventListener.METADATA_KEY_DRILLS)
            .containsEntry(TimelineEventListener.METADATA_KEY_EFFORT_RATING, 4);
    }

    @Test
    void homeworkAssigned_noResolvableDrills_writesNothing() {
        when(drillSnapshotResolver.snapshot(any())).thenReturn(List.of());

        listener.onHomeworkAssigned(new HomeworkAssignedEvent(bookingId, 7L, coachId, List.of(UUID.randomUUID())));

        verify(timelineRepository, never()).save(any());
        verify(timelineRepository, never()).appendHomeworkDrills(any(), any(), any());
    }
}
