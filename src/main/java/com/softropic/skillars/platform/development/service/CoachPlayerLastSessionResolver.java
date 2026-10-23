package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.development.contract.PlayerTimelineEventType;
import com.softropic.skillars.platform.development.repo.PlayerTimelineEvent;
import com.softropic.skillars.platform.development.repo.PlayerTimelineRepository;
import com.softropic.skillars.platform.development.repo.SluRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

// skillars-12-3 Task 2.1: merges the two "last completed, attended session with this coach"
// signals (the SLU-only proxy and the new coach-scoped timeline signal) into one reusable
// resolution point, so TimelineQueryService's access clock and SessionPrepService's recap
// fields can never silently disagree about which session they're each describing.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CoachPlayerLastSessionResolver {

    private final SluRepository sluRepository;
    private final PlayerTimelineRepository playerTimelineRepository;

    public LastCoachSessionSignal resolve(Long playerId, UUID coachId) {
        // Raw processing-time signals: ONLY for the recap-attribution decision below (isRecapAttributable).
        Instant sluDate = sluRepository.findLastSessionDate(playerId, coachId);
        Optional<PlayerTimelineEvent> timelineEvent = playerTimelineRepository
            .findTopByPlayerIdAndCoachIdAndEventTypeOrderByOccurredAtDesc(
                playerId, coachId, PlayerTimelineEventType.SESSION_COMPLETED);

        // skillars-12-10 AC4 (G21): the EXPOSED lastSessionDate is session time (the latest attended
        // booking's end, capped at now), not processing time, so a delayed SLU/timeline write cannot
        // shift the coach-access clock. Both are MAX aggregates over all attended rows -- never the
        // latest-processed row mapped to its booking, which would let a delayed older session shadow a
        // newer one. Skipped when the raw signal is absent (no attended row can exist then).
        Instant now = Instant.now(ClockProvider.getClock());
        Instant sluEnd = sluDate == null ? null : sluRepository.findLastSessionEnd(playerId, coachId, now);
        Instant timelineEnd = timelineEvent.isEmpty() ? null
            : playerTimelineRepository.findLastSessionEnd(playerId, coachId, now);
        Instant lastSessionDate = laterOf(sluEnd, timelineEnd);

        PlayerTimelineEvent recapSourceEventOrNull = timelineEvent
            .filter(evt -> isRecapAttributable(evt, sluDate, playerId, coachId))
            .orElse(null);

        return new LastCoachSessionSignal(lastSessionDate, recapSourceEventOrNull);
    }

    // skillars-12-3 code review (2026-10-15) Patch, owner decision: a pure occurredAt-vs-sluDate
    // timestamp comparison wrongly nulled the recap on the dominant happy path. For one and the same
    // attended builder session, TimelineEventListener.onBookingCompleted stamps occurredAt almost
    // immediately, while SluCalculationService.onBookingCompleted stamps calculated_at only after
    // loading the session/drills and computing SLU — both are separate @Async AFTER_COMMIT
    // listeners dispatched off the same commit, so calculated_at > occurred_at is the NORMAL
    // ordering for the SAME session, not proof a newer, different session exists. Fixed by
    // correlating on booking identity instead of a timing tolerance: only when the SLU row that
    // nominally "wins" the timestamp race belongs to a DIFFERENT booking than the one that produced
    // this timeline event do we treat it as a genuinely newer, recap-less session and null the recap.
    private boolean isRecapAttributable(PlayerTimelineEvent timelineEvent, Instant sluDate, Long playerId, UUID coachId) {
        if (sluDate == null || !timelineEvent.getOccurredAt().isBefore(sluDate)) {
            return true;
        }
        return sluRepository.findLastSessionBookingId(playerId, coachId)
            .map(bookingId -> bookingId.equals(timelineEvent.getReferenceId()))
            .orElse(false);
    }

    private static Instant laterOf(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    public record LastCoachSessionSignal(Instant lastSessionDate, PlayerTimelineEvent recapSourceEventOrNull) {
    }
}
