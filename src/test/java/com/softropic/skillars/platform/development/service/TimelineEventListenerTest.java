package com.softropic.skillars.platform.development.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.booking.contract.BookingCompletedEvent;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.repo.SessionCompletionData;
import com.softropic.skillars.platform.booking.repo.SessionCompletionDataRepository;
import com.softropic.skillars.platform.development.contract.PlayerTimelineEventType;
import com.softropic.skillars.platform.development.repo.PlayerTimelineEvent;
import com.softropic.skillars.platform.development.repo.PlayerTimelineRepository;
import com.softropic.skillars.platform.security.service.DevelopmentDataErasureGuard;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** skillars-12-8 AC1/AC2/AC7 at unit level. The async/REQUIRES_NEW wiring is covered by the ITs. */
@ExtendWith(MockitoExtension.class)
class TimelineEventListenerTest {

    @Mock private PlayerTimelineRepository timelineRepository;
    @Mock private SessionCompletionDataRepository completionDataRepository;
    @Mock private TimelineDrillSnapshotResolver drillSnapshotResolver;
    @Mock private DevelopmentDataErasureGuard erasureGuard;
    @Mock private BookingRepository bookingRepository;

    private TimelineEventListener listener;
    private final UUID bookingId = UUID.randomUUID();
    private final UUID coachId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new TimelineEventListener(timelineRepository, completionDataRepository,
            objectMapper, drillSnapshotResolver, erasureGuard, bookingRepository);
        ReflectionTestUtils.setField(listener, "self", listener);
        // skillars-deferred-163 AC3.1: most tests here don't care about the SESSION_COMPLETED
        // timestamp itself (they verify with any()) — default to a real booking row so the
        // production code's requestedEndTime lookup has something to find.
        Booking booking = new Booking();
        booking.setRequestedEndTime(java.time.Instant.now());
        lenient().when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
    }

    private BookingCompletedEvent completed(boolean attended, Integer effort, Integer focus, Integer technique) {
        return new BookingCompletedEvent(this, bookingId, coachId, 7L, 9L, attended, effort, focus, technique, List.of());
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * skillars-deferred-162 AC7: SESSION_COMPLETED is written by the native ON CONFLICT insert, not save(). Rebuilds
     * the row it would have inserted so the assertions read the same as for a saved entity.
     */
    @SuppressWarnings("unchecked")
    private PlayerTimelineEvent sessionCompletedRow() {
        ArgumentCaptor<Long> player = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<UUID> booking = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> coach = ArgumentCaptor.forClass(UUID.class);
        verify(timelineRepository).insertSessionCompletedIfAbsent(
            player.capture(), booking.capture(), any(), json.capture(), coach.capture());
        verify(timelineRepository, never()).save(any());
        PlayerTimelineEvent e = new PlayerTimelineEvent();
        e.setPlayerId(player.getValue());
        e.setEventType(PlayerTimelineEventType.SESSION_COMPLETED);
        e.setReferenceId(booking.getValue());
        e.setReferenceModule("booking");
        e.setCoachId(coach.getValue());
        try {
            e.setMetadata(objectMapper.readValue(json.getValue(), Map.class));
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
        return e;
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

        PlayerTimelineEvent e = sessionCompletedRow();
        assertThat(e.getReferenceId()).isEqualTo(bookingId);
        assertThat(e.getReferenceModule()).isEqualTo("booking");
        assertThat(e.getMetadata())
            .containsEntry(TimelineEventListener.METADATA_KEY_DRILLS, drills)
            .containsEntry(TimelineEventListener.METADATA_KEY_EFFORT_RATING, 4)
            .containsEntry(TimelineEventListener.METADATA_KEY_TECHNIQUE_RATING, 5)
            .doesNotContainKey(TimelineEventListener.METADATA_KEY_FOCUS_RATING);
        assertThat(e.getCoachId()).isEqualTo(coachId);
    }

    /**
     * skillars-deferred-163 AC3.1: SESSION_COMPLETED is timestamped with the booking's own
     * requestedEndTime, not wall-clock Instant.now() at write time.
     */
    @Test
    void attended_usesBookingRequestedEndTime_notWallClockNow() {
        when(completionDataRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());
        when(drillSnapshotResolver.forBooking(bookingId)).thenReturn(List.of());
        java.time.Instant sessionEndTime = java.time.Instant.now().minusSeconds(86_400);
        Booking booking = new Booking();
        booking.setRequestedEndTime(sessionEndTime);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));

        listener.onBookingCompleted(completed(true, 4, null, 5));

        verify(timelineRepository).insertSessionCompletedIfAbsent(
            eq(7L), eq(bookingId), eq(sessionEndTime), any(), eq(coachId));
    }

    /**
     * Code review 2026-10-27: a session ended (LIVE) or quick-completed before its scheduled end must not
     * be stamped in the future — the timestamp is capped at write time.
     */
    @Test
    void attended_requestedEndTimeInTheFuture_isCappedAtNow() {
        when(completionDataRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());
        when(drillSnapshotResolver.forBooking(bookingId)).thenReturn(List.of());
        Booking booking = new Booking();
        booking.setRequestedEndTime(java.time.Instant.now().plusSeconds(1800)); // ended 30 min early
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        java.time.Instant before = java.time.Instant.now();

        listener.onBookingCompleted(completed(true, 4, null, 5));

        org.mockito.ArgumentCaptor<java.time.Instant> at = org.mockito.ArgumentCaptor.forClass(java.time.Instant.class);
        verify(timelineRepository).insertSessionCompletedIfAbsent(eq(7L), eq(bookingId), at.capture(), any(), eq(coachId));
        assertThat(at.getValue()).isBetween(before, java.time.Instant.now());
    }

    /**
     * skillars-deferred-163 AC2.4 (code review 2026-10-27, Decision 1): a QUICK-mode booking names the
     * drills of the BLOCKS snapshot captured at wrap-up (forBlocks), not the live plan (forBooking), which
     * may have been edited since — the same blocks SluCalculationService computes SLU from.
     */
    @Test
    void attended_quickModeWithSnapshot_resolvesDrillsFromSnapshotBlocks_notLivePlan() throws Exception {
        UUID snapshotDrillId = UUID.randomUUID();
        List<com.softropic.skillars.platform.session.contract.SessionBlockData> blocks = List.of(
            new com.softropic.skillars.platform.session.contract.SessionBlockData("MAIN", "Main", 30,
                List.of(new com.softropic.skillars.platform.session.contract.SessionDrillRef(snapshotDrillId, 0))));
        SessionCompletionData scd = new SessionCompletionData();
        scd.setCompletionMode("QUICK");
        scd.setPlanBlocksSnapshot(objectMapper.writeValueAsString(blocks));
        when(completionDataRepository.findByBookingId(bookingId)).thenReturn(Optional.of(scd));
        List<Map<String, Object>> drills = List.of(Map.of("drillId", snapshotDrillId.toString(), "name", "Rondo"));
        when(drillSnapshotResolver.forBlocks(blocks, coachId)).thenReturn(drills);

        listener.onBookingCompleted(completed(true, 4, null, 5));

        assertThat(sessionCompletedRow().getMetadata()).containsEntry(TimelineEventListener.METADATA_KEY_DRILLS, drills);
        verify(drillSnapshotResolver, never()).forBooking(any());
    }

    /** An empty snapshot ("[]": no plan at wrap-up) is authoritative — a plan created later must not leak in. */
    @Test
    void attended_quickModeWithEmptySnapshot_showsNoDrills_andNeverReadsTheLivePlan() {
        SessionCompletionData scd = new SessionCompletionData();
        scd.setCompletionMode("QUICK");
        scd.setPlanBlocksSnapshot("[]");
        when(completionDataRepository.findByBookingId(bookingId)).thenReturn(Optional.of(scd));
        when(drillSnapshotResolver.forBlocks(List.of(), coachId)).thenReturn(List.of());

        listener.onBookingCompleted(completed(true, 4, null, 5));

        assertThat(sessionCompletedRow().getMetadata()).doesNotContainKey(TimelineEventListener.METADATA_KEY_DRILLS);
        verify(drillSnapshotResolver, never()).forBooking(any());
    }

    /**
     * A QUICK row with no snapshot column (pre-V170) or an unreadable one falls back to forBooking exactly
     * like LIVE mode — and SluCalculationService takes the same fallback through the same QuickPlanSnapshot.
     */
    @Test
    void attended_quickModeWithoutSnapshot_fallsBackToForBooking() {
        SessionCompletionData scd = new SessionCompletionData();
        scd.setCompletionMode("QUICK");
        when(completionDataRepository.findByBookingId(bookingId)).thenReturn(Optional.of(scd));
        when(drillSnapshotResolver.forBooking(bookingId)).thenReturn(List.of());

        listener.onBookingCompleted(completed(true, 4, null, 5));

        verify(drillSnapshotResolver).forBooking(bookingId);
        verify(drillSnapshotResolver, never()).forBlocks(any(), any());
    }

    @Test
    void attended_quickModeWithUnreadableSnapshot_fallsBackToForBooking() {
        SessionCompletionData scd = new SessionCompletionData();
        scd.setCompletionMode("QUICK");
        scd.setPlanBlocksSnapshot("{not json");
        when(completionDataRepository.findByBookingId(bookingId)).thenReturn(Optional.of(scd));
        when(drillSnapshotResolver.forBooking(bookingId)).thenReturn(List.of());

        listener.onBookingCompleted(completed(true, 4, null, 5));

        verify(drillSnapshotResolver).forBooking(bookingId);
        verify(drillSnapshotResolver, never()).forBlocks(any(), any());
    }

    @Test
    void attended_noDrills_omitsDrillsKey() {
        when(completionDataRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());
        when(drillSnapshotResolver.forBooking(bookingId)).thenReturn(List.of());

        listener.onBookingCompleted(completed(true, null, null, null));

        assertThat(sessionCompletedRow().getMetadata()).doesNotContainKeys(
            TimelineEventListener.METADATA_KEY_DRILLS, TimelineEventListener.METADATA_KEY_EFFORT_RATING);
    }

    @Test
    void unattended_hasNoDrillsNoRatings_andNoAccessClockCoach() {
        when(completionDataRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());

        listener.onBookingCompleted(completed(false, 3, 3, 3));

        PlayerTimelineEvent e = sessionCompletedRow();
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
        when(drillSnapshotResolver.snapshot(eq(List.of(d1)), eq(coachId))).thenReturn(drills);

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
        when(drillSnapshotResolver.snapshot(eq(List.of(d2)), any())).thenReturn(drills);
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

        PlayerTimelineEvent e = sessionCompletedRow();
        assertThat(e.getEventType()).isEqualTo(PlayerTimelineEventType.SESSION_COMPLETED);
        assertThat(e.getMetadata()).doesNotContainKey(TimelineEventListener.METADATA_KEY_DRILLS)
            .containsEntry(TimelineEventListener.METADATA_KEY_EFFORT_RATING, 4);
    }

    @Test
    void homeworkAssigned_noResolvableDrills_writesNothing() {
        when(drillSnapshotResolver.snapshot(any(), any())).thenReturn(List.of());

        listener.onHomeworkAssigned(new HomeworkAssignedEvent(bookingId, 7L, coachId, List.of(UUID.randomUUID())));

        verify(timelineRepository, never()).save(any());
        verify(timelineRepository, never()).appendHomeworkDrills(any(), any(), any());
    }

    // skillars-deferred-162 AC5.5: an erased player gets no development data from these listeners.

    @Test
    void bookingCompleted_erasedPlayer_writesNothing() {
        when(completionDataRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());
        when(drillSnapshotResolver.forBooking(bookingId)).thenReturn(List.of());
        when(erasureGuard.isErasedUnderLock(eq(7L), anyString())).thenReturn(true);

        listener.onBookingCompleted(completed(true, 4, 4, 4));

        verify(timelineRepository, never()).insertSessionCompletedIfAbsent(any(), any(), any(), any(), any());
        verify(timelineRepository, never()).save(any());
    }

    @Test
    void homeworkAssigned_erasedPlayer_writesNothing() {
        UUID d1 = UUID.randomUUID();
        when(drillSnapshotResolver.snapshot(eq(List.of(d1)), eq(coachId)))
            .thenReturn(List.of(Map.of("drillId", d1.toString(), "name", "Passing")));
        when(erasureGuard.isErasedUnderLock(eq(7L), anyString())).thenReturn(true);

        listener.onHomeworkAssigned(new HomeworkAssignedEvent(bookingId, 7L, coachId, List.of(d1)));

        verifyNoInteractions(timelineRepository);
    }

    // skillars-deferred-162 AC7.6: a replayed BookingCompletedEvent is a quiet no-op, never a second row.

    @Test
    void bookingCompleted_replay_insertsThroughOnConflict_andWritesNoSecondRow() {
        when(completionDataRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());
        when(drillSnapshotResolver.forBooking(bookingId)).thenReturn(List.of());
        when(timelineRepository.insertSessionCompletedIfAbsent(eq(7L), eq(bookingId), any(), anyString(), eq(coachId)))
            .thenReturn(1, 0);

        listener.onBookingCompleted(completed(true, 4, 4, 4));
        listener.onBookingCompleted(completed(true, 4, 4, 4));

        verify(timelineRepository, org.mockito.Mockito.times(2))
            .insertSessionCompletedIfAbsent(eq(7L), eq(bookingId), any(), anyString(), eq(coachId));
        verify(timelineRepository, never()).save(any());
    }
}
