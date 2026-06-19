package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.development.contract.TimelineEventResponse;
import com.softropic.skillars.platform.development.contract.TimelineResponse;
import com.softropic.skillars.platform.development.repo.PlayerTimelineRepository;
import com.softropic.skillars.platform.development.repo.SluRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimelineQueryService {

    private final PlayerTimelineRepository timelineRepository;
    private final SluRepository sluRepository;
    private final ConfigService configService;

    public TimelineResponse getTimeline(Long playerId, boolean isCoach, UUID coachId) {
        long expiryDays;
        try {
            expiryDays = configService.getLong("development.timeline.coachAccessExpiryDays");
        } catch (Exception e) {
            log.warn("Config key 'development.timeline.coachAccessExpiryDays' missing — defaulting to 90 days", e);
            expiryDays = 90L;
        }

        if (isCoach) {
            // "Activity" = most recent completed session (SLU record) for this coach-player pair.
            // Report generation and radar assessments do NOT reset the access clock.
            Instant lastSession = sluRepository.findLastSessionDate(playerId, coachId);
            boolean expired = lastSession == null ||
                lastSession.isBefore(Instant.now().minus(expiryDays, ChronoUnit.DAYS));
            if (expired) {
                return new TimelineResponse(true, expiryDays, List.of());
            }
        }

        List<TimelineEventResponse> events = timelineRepository
            .findByPlayerIdOrderByOccurredAtDesc(playerId)
            .stream()
            .map(e -> new TimelineEventResponse(
                e.getId(), e.getEventType(), e.getReferenceId(),
                e.getReferenceModule(), e.getOccurredAt(), e.getMetadata()))
            .toList();
        return new TimelineResponse(false, null, events);
    }
}
