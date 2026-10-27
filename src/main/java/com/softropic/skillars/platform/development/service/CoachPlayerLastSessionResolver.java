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
import java.util.function.Supplier;

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
        return resolve(playerId, coachId, null, null);
    }

    // skillars-deferred-165 AC9: overload used by SessionPrepService so the RECAP source
    // (lastWhatWeDid/lastWhatsNext) can be bounded by the prepped booking's own requestedStartTime --
    // mirroring PlayerTimelineRepository.findLatestWhatsNextSuggestion's existing bound for the
    // sibling suggestion field. preppedBookingId/preppedBookingStartTime are null for every other
    // caller (TimelineQueryService, CoachRosterService, ReportGenerationService), none of which reads
    // recapSourceEventOrNull() -- only lastSessionDate() -- so they keep the prior unbounded behavior
    // unchanged. lastSessionDate() itself is ALWAYS computed from the unbounded timelineEvent/sluDate
    // below, in both overloads: it is a different signal (the coach-access clock) that this AC does
    // not touch.
    public LastCoachSessionSignal resolve(
            Long playerId, UUID coachId, UUID preppedBookingId, Instant preppedBookingStartTime) {
        // Raw processing-time presence signals: whether an SLU row / timeline event exists at all.
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

        boolean bounded = preppedBookingStartTime != null;
        Optional<PlayerTimelineEvent> recapCandidate = bounded
            ? playerTimelineRepository.findLatestSessionCompletedEvent(
                playerId, coachId, preppedBookingId, preppedBookingStartTime)
            : timelineEvent;
        // skillars-deferred-165 code review (2026-10-27): a bounded candidate must be checked against the
        // SAME-bounded SLU signal -- otherwise a later-scheduled session's SLU row (excluded from the
        // candidate) still "wins" the attributability check and nulls the earlier, correct recap.
        Instant recapSluEnd = !bounded || sluDate == null ? sluEnd
            : sluRepository.findLastSessionEndBefore(playerId, coachId, now, preppedBookingId, preppedBookingStartTime);
        Supplier<Optional<UUID>> lastSluBookingId = bounded
            ? () -> sluRepository.findLastSessionBookingIdBefore(playerId, coachId, preppedBookingId, preppedBookingStartTime)
            : () -> sluRepository.findLastSessionBookingId(playerId, coachId);
        PlayerTimelineEvent recapSourceEventOrNull = recapCandidate
            .filter(evt -> isRecapAttributable(evt, recapSluEnd, lastSluBookingId))
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
    //
    // skillars-deferred-163 code review (2026-10-27, Decision 2): compared in SESSION time on both sides.
    // AC3.1 made occurredAt the session's end (capped at write time), so comparing it against the raw
    // processing-time sluDate let an older booking confirmed late (SLU processed last) look newer than a
    // genuinely newer session and null that session's recap. sluEnd (MAX booking end over SLU rows) and
    // findLastSessionBookingId (now ordered by booking end too) are the session-time counterparts; for one
    // and the same session the two ends are equal, so the fast path is again the common case.
    private boolean isRecapAttributable(PlayerTimelineEvent timelineEvent, Instant sluEnd,
                                        Supplier<Optional<UUID>> lastSluBookingId) {
        if (sluEnd == null || !timelineEvent.getOccurredAt().isBefore(sluEnd)) {
            return true;
        }
        return lastSluBookingId.get()
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
