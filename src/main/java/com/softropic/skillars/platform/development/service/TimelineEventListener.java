package com.softropic.skillars.platform.development.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.booking.contract.BookingCompletedEvent;
import com.softropic.skillars.platform.booking.contract.SessionRecapUpdatedEvent;
import com.softropic.skillars.platform.booking.repo.SessionCompletionData;
import com.softropic.skillars.platform.booking.repo.SessionCompletionDataRepository;
import com.softropic.skillars.platform.development.contract.PlayerTimelineEventType;
import com.softropic.skillars.platform.development.contract.RadarEntrySubmittedEvent;
import com.softropic.skillars.platform.development.repo.PlayerTimelineEvent;
import com.softropic.skillars.platform.development.repo.PlayerTimelineRepository;
import com.softropic.skillars.platform.session.contract.HomeworkAssignedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TimelineEventListener {

    // skillars-12-2 code review (P2): shared with TimelineQueryService's redaction denylist so
    // the two metadata-key spellings can't drift apart — not a guarantee that every FUTURE key
    // added here gets redacted there (that would need an allowlist), only that these three don't.
    public static final String METADATA_KEY_COACH_ID = "coachId";
    public static final String METADATA_KEY_WHAT_WE_DID = "whatWeDid";
    public static final String METADATA_KEY_WHATS_NEXT = "whatsNext";
    public static final String METADATA_KEY_WHAT_WENT_WELL = "whatWentWell";
    // skillars-12-8: drills is visible to every viewer; the three ratings are authoring-coach only and
    // MUST stay in TimelineQueryService's redaction denylist (a new key not added there leaks).
    public static final String METADATA_KEY_DRILLS = "drills";
    public static final String METADATA_KEY_EFFORT_RATING = "effortRating";
    public static final String METADATA_KEY_FOCUS_RATING = "focusRating";
    public static final String METADATA_KEY_TECHNIQUE_RATING = "techniqueRating";

    private final PlayerTimelineRepository timelineRepository;
    private final SessionCompletionDataRepository completionDataRepository;
    private final ObjectMapper objectMapper;
    private final TimelineDrillSnapshotResolver drillSnapshotResolver;

    @Autowired
    @Lazy
    private TimelineEventListener self;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("taskExecutor")
    public void onBookingCompleted(BookingCompletedEvent event) {
        try {
            // skillars-12-2 AC3: Map.of(...) rejects null values outright, and null recap
            // fields are a real possibility here from two sources — not just the back-compat
            // 10-arg BookingCompletedEvent constructor (Task 2.1), which has zero production
            // callers (its 10 remaining call sites are all tests): a production publisher using
            // the 12-arg constructor can still pass real nulls for any booking whose
            // session_completion_data row predates the V159 migration (the two columns are
            // nullable with no backfill). So the recap keys are added conditionally, not
            // unconditionally — do not "clean up" this guard by reverting to Map.of(...).
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(METADATA_KEY_COACH_ID, event.getCoachId().toString());
            // skillars-12-7 code review: prefer the CURRENT session_completion_data text over the
            // event-carried copy. Both this listener and onSessionRecapUpdated run async after commit, so
            // a recap edit saved right after completion can be propagated first (0 rows, WARN) and this
            // insert would then freeze the pre-edit text into the timeline for good. The event copy is
            // the fallback only when the row is gone.
            SessionCompletionData scd = completionDataRepository.findByBookingId(event.getBookingId()).orElse(null);
            String whatWeDid = scd != null ? scd.getWhatWeDid() : event.getWhatWeDid();
            String whatsNext = scd != null ? scd.getWhatsNext() : event.getWhatsNext();
            String whatWentWell = scd != null ? scd.getWhatWentWell() : event.getWhatWentWell();
            if (whatWeDid != null) metadata.put(METADATA_KEY_WHAT_WE_DID, whatWeDid);
            if (whatsNext != null) metadata.put(METADATA_KEY_WHATS_NEXT, whatsNext);
            if (whatWentWell != null) metadata.put(METADATA_KEY_WHAT_WENT_WELL, whatWentWell);
            // skillars-12-8 AC1/AC2: what was trained + the coach's ratings, attended sessions only (a no-show
            // trained nothing and its ratings are not timeline content). Enrichment is best-effort: the
            // resolver never throws, so the SESSION_COMPLETED row is always written.
            if (event.isPlayerAttended()) {
                // The resolver already never throws; this guard keeps that guarantee local, so a future resolver
                // change cannot cost the SESSION_COMPLETED row (code review 2026-10-23).
                try {
                    List<Map<String, Object>> drills = drillSnapshotResolver.forBooking(event.getBookingId());
                    if (!drills.isEmpty()) metadata.put(METADATA_KEY_DRILLS, drills);
                } catch (Exception e) {
                    log.warn("Drill enrichment failed for booking {} -- writing SESSION_COMPLETED without drills",
                        event.getBookingId(), e);
                }
                if (event.getEffortRating() != null) metadata.put(METADATA_KEY_EFFORT_RATING, event.getEffortRating());
                if (event.getFocusRating() != null) metadata.put(METADATA_KEY_FOCUS_RATING, event.getFocusRating());
                if (event.getTechniqueRating() != null) metadata.put(METADATA_KEY_TECHNIQUE_RATING, event.getTechniqueRating());
            }
            // skillars-12-3 AC1: a non-attended wrap-up must not earn coach timeline access any
            // more than it earns an SLU row today — mirrors SluCalculationService.onBookingCompleted's
            // own !event.isPlayerAttended() early return exactly.
            UUID coachIdForClock = event.isPlayerAttended() ? event.getCoachId() : null;
            self.writeTimelineEvent(
                event.getPlayerId(),
                PlayerTimelineEventType.SESSION_COMPLETED,
                event.getBookingId(),
                "booking",
                metadata,
                coachIdForClock
            );
        } catch (Exception e) {
            log.error("Failed to write SESSION_COMPLETED timeline event: playerId={}", event.getPlayerId(), e);
        }
    }

    /**
     * skillars-12-7 AC4/AC5: propagates an edited recap onto the existing SESSION_COMPLETED row. The
     * listener re-reads the CURRENT session_completion_data row rather than trusting event-carried text, so
     * two quick edits applied out of order on the async executor converge on the source of truth. The re-read
     * takes the row's write lock (code review): a coach erasure scrubs session_completion_data and then the
     * timeline in one transaction, so it either commits before this read (we see the scrubbed row and strip
     * the keys) or waits for this transaction and scrubs the timeline after our UPDATE. An unlocked read
     * could see the pre-scrub text and write it back over an already-committed timeline scrub. Lock order is
     * SCD then timeline, the same as the coach scrub. The PLAYER erasure goes the other way (timeline delete,
     * then SCD), so a propagation racing that same player's erasure can deadlock; Postgres aborts one side and
     * both outcomes are safe -- an aborted erasure is alerted and re-run by the retry scheduler, an aborted
     * propagation is moot because the erasure deletes the player's timeline rows anyway.
     * If the timeline row is missing (its original write failed) this logs a WARN and does nothing -- it
     * never creates one; the session_completion_data row stays the source of truth.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("taskExecutor")
    public void onSessionRecapUpdated(SessionRecapUpdatedEvent event) {
        try {
            self.updateSessionRecapMetadata(event.getPlayerId(), event.getBookingId(), event.getCoachId());
        } catch (Exception e) {
            log.error("Failed to propagate recap edit to SESSION_COMPLETED timeline event: bookingId={}",
                event.getBookingId(), e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateSessionRecapMetadata(Long playerId, UUID bookingId, UUID coachId) {
        SessionCompletionData scd = completionDataRepository.findForUpdateByBookingId(bookingId).orElse(null);
        if (scd == null) {
            log.warn("Recap edit for booking {} but no session_completion_data row -- timeline left untouched", bookingId);
            return;
        }
        Map<String, Object> patch = new HashMap<>();
        if (scd.getWhatWeDid() != null) patch.put(METADATA_KEY_WHAT_WE_DID, scd.getWhatWeDid());
        if (scd.getWhatsNext() != null) patch.put(METADATA_KEY_WHATS_NEXT, scd.getWhatsNext());
        if (scd.getWhatWentWell() != null) patch.put(METADATA_KEY_WHAT_WENT_WELL, scd.getWhatWentWell());
        String json;
        try {
            json = objectMapper.writeValueAsString(patch);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise recap patch for booking " + bookingId, e);
        }
        int updated = timelineRepository.updateSessionRecapMetadata(playerId, bookingId, coachId, json);
        if (updated == 0) {
            log.warn("Recap edit for booking {} matched no SESSION_COMPLETED timeline event -- nothing to update", bookingId);
        }
    }

    /**
     * skillars-12-8 AC7: HOMEWORK_ASSIGNED producer's consumer. fallbackExecution = true is REQUIRED: the
     * publisher (HomeworkAssignmentService.handleBookingCompleted) has no active transaction, and an
     * AFTER_COMMIT listener without fallback is silently dropped then. Written via the 5-arg overload so
     * the coach_id column stays NULL -- that column is the attendance-gated coach-access-clock signal and a
     * homework entry must never reset it. One row per (player, booking): the publisher announces only the
     * drill ids it newly persisted, so a booking can legitimately produce a SECOND event (drill 2 failed
     * mid-loop, a later replay inserts it). That event is merged into the existing row (drill ids already
     * present are skipped), never dropped and never written as a second row (code review 2026-10-23).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Async("taskExecutor")
    public void onHomeworkAssigned(HomeworkAssignedEvent event) {
        try {
            List<Map<String, Object>> drills = drillSnapshotResolver.snapshot(event.drillIds());
            if (drills.isEmpty()) {
                return;
            }
            if (timelineRepository.existsByPlayerIdAndEventTypeAndReferenceId(
                    event.playerId(), PlayerTimelineEventType.HOMEWORK_ASSIGNED, event.bookingId())) {
                self.appendHomeworkDrills(event.playerId(), event.bookingId(), drills);
                return;
            }
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(METADATA_KEY_DRILLS, drills);
            metadata.put(METADATA_KEY_COACH_ID, event.coachId().toString());
            self.writeTimelineEvent(event.playerId(), PlayerTimelineEventType.HOMEWORK_ASSIGNED,
                event.bookingId(), "booking", metadata);
        } catch (Exception e) {
            log.error("Failed to write HOMEWORK_ASSIGNED timeline event: playerId={}", event.playerId(), e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void appendHomeworkDrills(Long playerId, UUID bookingId, List<Map<String, Object>> drills) {
        String json;
        try {
            json = objectMapper.writeValueAsString(drills);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise homework drills for booking " + bookingId, e);
        }
        int updated = timelineRepository.appendHomeworkDrills(playerId, bookingId, json);
        log.debug("Merged homework drills into existing HOMEWORK_ASSIGNED row for booking {} ({} row(s))", bookingId, updated);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("taskExecutor")
    public void onRadarEntrySubmitted(RadarEntrySubmittedEvent event) {
        try {
            self.writeTimelineEvent(
                event.playerId(),
                PlayerTimelineEventType.RADAR_ASSESSMENT,
                null,
                "development",
                Map.of("skillCodes", event.skillCodes())
            );
        } catch (Exception e) {
            log.error("Failed to write RADAR_ASSESSMENT timeline event: playerId={}", event.playerId(), e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeTimelineEvent(Long playerId, PlayerTimelineEventType type,
                                   UUID referenceId, String referenceModule,
                                   Map<String, Object> metadata) {
        writeTimelineEvent(playerId, type, referenceId, referenceModule, metadata, null);
    }

    // skillars-12-3 Task 1.3: coachId is the attendance-gated signal CoachPlayerLastSessionResolver
    // reads to merge with the SLU-only clock (AC1) — null for RADAR_ASSESSMENT/PERFORMANCE_REPORT,
    // whose source events (RadarEntrySubmittedEvent, ReportGeneratedEvent) carry nothing resolvable
    // to a coach id usable here, and for a non-attended SESSION_COMPLETED.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeTimelineEvent(Long playerId, PlayerTimelineEventType type,
                                   UUID referenceId, String referenceModule,
                                   Map<String, Object> metadata, UUID coachId) {
        PlayerTimelineEvent evt = new PlayerTimelineEvent();
        evt.setPlayerId(playerId);
        evt.setEventType(type);
        evt.setReferenceId(referenceId);
        evt.setReferenceModule(referenceModule);
        evt.setOccurredAt(Instant.now());
        evt.setMetadata(metadata);
        evt.setCoachId(coachId);
        timelineRepository.save(evt);
    }
}
