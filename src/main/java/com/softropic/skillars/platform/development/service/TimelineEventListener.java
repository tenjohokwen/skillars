package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.booking.contract.BookingCompletedEvent;
import com.softropic.skillars.platform.development.contract.PlayerTimelineEventType;
import com.softropic.skillars.platform.development.contract.RadarEntrySubmittedEvent;
import com.softropic.skillars.platform.development.repo.PlayerTimelineEvent;
import com.softropic.skillars.platform.development.repo.PlayerTimelineRepository;
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

    private final PlayerTimelineRepository timelineRepository;

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
            if (event.getWhatWeDid() != null) metadata.put(METADATA_KEY_WHAT_WE_DID, event.getWhatWeDid());
            if (event.getWhatsNext() != null) metadata.put(METADATA_KEY_WHATS_NEXT, event.getWhatsNext());
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
