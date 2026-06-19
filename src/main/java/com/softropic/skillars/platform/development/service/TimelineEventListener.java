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
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TimelineEventListener {

    private final PlayerTimelineRepository timelineRepository;

    @Autowired
    @Lazy
    private TimelineEventListener self;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onBookingCompleted(BookingCompletedEvent event) {
        try {
            self.writeTimelineEvent(
                event.getPlayerId(),
                PlayerTimelineEventType.SESSION_COMPLETED,
                event.getBookingId(),
                "booking",
                Map.of("coachId", event.getCoachId().toString())
            );
        } catch (Exception e) {
            log.error("Failed to write SESSION_COMPLETED timeline event: playerId={}", event.getPlayerId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
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
        PlayerTimelineEvent evt = new PlayerTimelineEvent();
        evt.setPlayerId(playerId);
        evt.setEventType(type);
        evt.setReferenceId(referenceId);
        evt.setReferenceModule(referenceModule);
        evt.setOccurredAt(Instant.now());
        evt.setMetadata(metadata);
        timelineRepository.save(evt);
    }
}
