package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.contract.PlayerTimelineEventType;
import com.softropic.skillars.platform.development.repo.PlayerTimelineEvent;
import com.softropic.skillars.platform.development.repo.PlayerTimelineRepository;
import com.softropic.skillars.platform.development.repo.SluRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-152 code review (2026-10-17): this class had NO unit test at all, despite being
 * the single resolution point three authorization surfaces now share — {@code CoachRosterService},
 * {@code TimelineQueryService} and {@code SessionPrepService}, joined by
 * {@code ReportGenerationService} in skillars-deferred-152 AC3.
 *
 * <p>The gap mattered because AC3's widening is defined entirely by this merge: a Quick-Complete-only
 * coach (no SLU row, only a coach-scoped {@code SESSION_COMPLETED} timeline event) must now resolve
 * to a non-null {@code lastSessionDate} so the report gate admits them. That behaviour was reachable
 * only through {@code SessionPrepResourceIT}'s real-DB fixtures; every service-level test mocks this
 * resolver, so mocking it cannot distinguish an SLU-backed signal from a timeline-only one — the
 * AC3 test in {@code ReportGenerationServiceTest} can only pin the call-site swap.
 *
 * <p>The second half of the contract is recap attribution. Per skillars-12-3's code review, a later
 * {@code calculated_at} than {@code occurred_at} is the NORMAL ordering for one and the same attended
 * session (two separate {@code @Async} AFTER_COMMIT listeners off one commit), so the recap is nulled
 * only when the SLU row that wins the timestamp race belongs to a DIFFERENT booking. Both arms of that
 * correlation, and the exact-equality boundary, are pinned below.
 */
@ExtendWith(MockitoExtension.class)
class CoachPlayerLastSessionResolverTest {

    private static final Long PLAYER_ID = 4242L;
    private static final UUID COACH_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    @Mock private SluRepository sluRepository;
    @Mock private PlayerTimelineRepository playerTimelineRepository;

    private CoachPlayerLastSessionResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new CoachPlayerLastSessionResolver(sluRepository, playerTimelineRepository);
    }

    // ── The merged signal ───────────────────────────────────────────────────

    @Test
    void resolve_noSluRowAndNoTimelineEvent_returnsEmptySignal() {
        stubSluDate(null);
        stubTimelineEvent(Optional.empty());

        CoachPlayerLastSessionResolver.LastCoachSessionSignal signal = resolver.resolve(PLAYER_ID, COACH_ID);

        // This null is what every consumer's access gate denies on.
        assertThat(signal.lastSessionDate()).isNull();
        assertThat(signal.recapSourceEventOrNull()).isNull();
    }

    @Test
    void resolve_sluRowOnly_returnsSluDateWithNoRecapSource() {
        Instant sluDate = NOW.minusSeconds(3600);
        stubSluDate(sluDate);
        stubTimelineEvent(Optional.empty());

        CoachPlayerLastSessionResolver.LastCoachSessionSignal signal = resolver.resolve(PLAYER_ID, COACH_ID);

        assertThat(signal.lastSessionDate()).isEqualTo(sluDate);
        // No timeline event exists, so there is nothing to source a recap from.
        assertThat(signal.recapSourceEventOrNull()).isNull();
    }

    /**
     * skillars-deferred-152 AC3's whole point: the Quick-Complete-only coach. Before the merge this
     * resolver replaced, a coach with zero SLU rows read as "no session history with this player"
     * and was denied — now the coach-scoped timeline event alone carries the signal.
     */
    @Test
    void resolve_timelineEventOnlyAndNoSluRow_returnsTimelineDateAndKeepsRecapSource() {
        Instant occurredAt = NOW.minusSeconds(7200);
        PlayerTimelineEvent event = sessionCompletedEvent(occurredAt, UUID.randomUUID());
        stubSluDate(null);
        stubTimelineEvent(Optional.of(event));

        CoachPlayerLastSessionResolver.LastCoachSessionSignal signal = resolver.resolve(PLAYER_ID, COACH_ID);

        assertThat(signal.lastSessionDate()).isEqualTo(occurredAt);
        assertThat(signal.recapSourceEventOrNull()).isSameAs(event);
        // A null sluDate short-circuits attribution — no booking correlation query is needed.
        verify(sluRepository, never()).findLastSessionBookingId(any(), any());
    }

    @Test
    void resolve_bothSignalsPresent_returnsTheLaterOfTheTwo() {
        Instant sluDate = NOW.minusSeconds(10_000);
        Instant occurredAt = NOW.minusSeconds(60);
        stubSluDate(sluDate);
        stubTimelineEvent(Optional.of(sessionCompletedEvent(occurredAt, UUID.randomUUID())));

        CoachPlayerLastSessionResolver.LastCoachSessionSignal signal = resolver.resolve(PLAYER_ID, COACH_ID);

        assertThat(signal.lastSessionDate()).isEqualTo(occurredAt);
    }

    @Test
    void resolve_queriesTheCoachScopedSessionCompletedEventSpecifically() {
        stubSluDate(null);
        stubTimelineEvent(Optional.empty());

        resolver.resolve(PLAYER_ID, COACH_ID);

        // Scoped to this coach AND to SESSION_COMPLETED: a non-attended Quick Complete writes
        // coach_id = null and so must never grant access through this signal.
        verify(playerTimelineRepository).findTopByPlayerIdAndCoachIdAndEventTypeOrderByOccurredAtDesc(
            PLAYER_ID, COACH_ID, PlayerTimelineEventType.SESSION_COMPLETED);
    }

    // ── Recap attribution ───────────────────────────────────────────────────

    @Test
    void resolve_timelineEventNewerThanSluRow_keepsRecapWithoutBookingCorrelation() {
        Instant sluDate = NOW.minusSeconds(10_000);
        PlayerTimelineEvent event = sessionCompletedEvent(NOW.minusSeconds(60), UUID.randomUUID());
        stubSluDate(sluDate);
        stubTimelineEvent(Optional.of(event));

        CoachPlayerLastSessionResolver.LastCoachSessionSignal signal = resolver.resolve(PLAYER_ID, COACH_ID);

        assertThat(signal.recapSourceEventOrNull()).isSameAs(event);
        verify(sluRepository, never()).findLastSessionBookingId(any(), any());
    }

    /**
     * Boundary: attribution is {@code !occurredAt.isBefore(sluDate)}, so the two timestamps being
     * exactly equal keeps the recap and takes no correlation query — the inclusive side of the
     * comparison, one tick away from the branch exercised below.
     */
    @Test
    void resolve_timelineEventExactlyEqualToSluRow_keepsRecapWithoutBookingCorrelation() {
        Instant sameInstant = NOW.minusSeconds(3600);
        PlayerTimelineEvent event = sessionCompletedEvent(sameInstant, UUID.randomUUID());
        stubSluDate(sameInstant);
        stubTimelineEvent(Optional.of(event));

        CoachPlayerLastSessionResolver.LastCoachSessionSignal signal = resolver.resolve(PLAYER_ID, COACH_ID);

        assertThat(signal.lastSessionDate()).isEqualTo(sameInstant);
        assertThat(signal.recapSourceEventOrNull()).isSameAs(event);
        verify(sluRepository, never()).findLastSessionBookingId(any(), any());
    }

    /**
     * The dominant happy path skillars-12-3's code review fixed: for ONE attended builder session,
     * {@code calculated_at > occurred_at} is the normal write ordering, so a pure timestamp
     * comparison wrongly nulled the recap. Correlating on booking identity keeps it.
     */
    @Test
    void resolve_sluRowWinsTimestampRaceButSameBooking_keepsRecap() {
        UUID bookingId = UUID.randomUUID();
        Instant occurredAt = NOW.minusSeconds(3600);
        PlayerTimelineEvent event = sessionCompletedEvent(occurredAt, bookingId);
        stubSluDate(occurredAt.plusSeconds(5));
        stubTimelineEvent(Optional.of(event));
        when(sluRepository.findLastSessionBookingId(PLAYER_ID, COACH_ID)).thenReturn(Optional.of(bookingId));

        CoachPlayerLastSessionResolver.LastCoachSessionSignal signal = resolver.resolve(PLAYER_ID, COACH_ID);

        assertThat(signal.recapSourceEventOrNull()).isSameAs(event);
    }

    @Test
    void resolve_sluRowWinsTimestampRaceAndDifferentBooking_nullsRecapButKeepsLaterSluDate() {
        Instant occurredAt = NOW.minusSeconds(86_400);
        Instant sluDate = NOW.minusSeconds(60);
        stubSluDate(sluDate);
        stubTimelineEvent(Optional.of(sessionCompletedEvent(occurredAt, UUID.randomUUID())));
        when(sluRepository.findLastSessionBookingId(PLAYER_ID, COACH_ID)).thenReturn(Optional.of(UUID.randomUUID()));

        CoachPlayerLastSessionResolver.LastCoachSessionSignal signal = resolver.resolve(PLAYER_ID, COACH_ID);

        // A genuinely newer, recap-less session: access still granted, but the stale recap is dropped.
        assertThat(signal.lastSessionDate()).isEqualTo(sluDate);
        assertThat(signal.recapSourceEventOrNull()).isNull();
    }

    @Test
    void resolve_sluRowWinsTimestampRaceButBookingIdUnavailable_nullsRecap() {
        Instant occurredAt = NOW.minusSeconds(86_400);
        stubSluDate(NOW.minusSeconds(60));
        stubTimelineEvent(Optional.of(sessionCompletedEvent(occurredAt, UUID.randomUUID())));
        // Cannot prove the two belong to the same booking → orElse(false), i.e. fail closed.
        when(sluRepository.findLastSessionBookingId(PLAYER_ID, COACH_ID)).thenReturn(Optional.empty());

        CoachPlayerLastSessionResolver.LastCoachSessionSignal signal = resolver.resolve(PLAYER_ID, COACH_ID);

        assertThat(signal.recapSourceEventOrNull()).isNull();
    }

    @Test
    void resolve_sluRowWinsTimestampRaceAndEventHasNoReferenceId_nullsRecap() {
        Instant occurredAt = NOW.minusSeconds(86_400);
        stubSluDate(NOW.minusSeconds(60));
        stubTimelineEvent(Optional.of(sessionCompletedEvent(occurredAt, null)));
        when(sluRepository.findLastSessionBookingId(PLAYER_ID, COACH_ID)).thenReturn(Optional.of(UUID.randomUUID()));

        CoachPlayerLastSessionResolver.LastCoachSessionSignal signal = resolver.resolve(PLAYER_ID, COACH_ID);

        // reference_id is nullable on player_timeline_events; the comparison is bookingId.equals(ref),
        // so a null referenceId cannot match and must not NPE.
        assertThat(signal.recapSourceEventOrNull()).isNull();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void stubSluDate(Instant sluDate) {
        when(sluRepository.findLastSessionDate(PLAYER_ID, COACH_ID)).thenReturn(sluDate);
    }

    private void stubTimelineEvent(Optional<PlayerTimelineEvent> event) {
        when(playerTimelineRepository.findTopByPlayerIdAndCoachIdAndEventTypeOrderByOccurredAtDesc(
            PLAYER_ID, COACH_ID, PlayerTimelineEventType.SESSION_COMPLETED)).thenReturn(event);
    }

    private PlayerTimelineEvent sessionCompletedEvent(Instant occurredAt, UUID referenceId) {
        PlayerTimelineEvent event = new PlayerTimelineEvent();
        event.setId(UUID.randomUUID());
        event.setPlayerId(PLAYER_ID);
        event.setCoachId(COACH_ID);
        event.setEventType(PlayerTimelineEventType.SESSION_COMPLETED);
        event.setOccurredAt(occurredAt);
        event.setReferenceId(referenceId);
        return event;
    }
}
