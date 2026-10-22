package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.development.contract.PlayerTimelineEventType;
import com.softropic.skillars.platform.development.contract.TimelineEventResponse;
import com.softropic.skillars.platform.development.contract.TimelineResponse;
import com.softropic.skillars.platform.development.repo.PlayerTimelineEvent;
import com.softropic.skillars.platform.development.repo.PlayerTimelineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimelineQueryService {

    private final PlayerTimelineRepository timelineRepository;
    private final ConfigService configService;
    private final CoachPlayerLastSessionResolver coachPlayerLastSessionResolver;

    public TimelineResponse getTimeline(Long playerId, boolean isCoach, UUID coachId, int page, int size) {
        long expiryDays;
        try {
            // skillars-deferred-107 AC2: 0/neg → coach timeline access reads as always expired.
            // A missing key still throws (caught below → 90d fallback); a present bad value clamps.
            expiryDays = configService.getBoundedLong("development.timeline.coachAccessExpiryDays", 1L, 3650L);
        } catch (Exception e) {
            log.warn("Config key 'development.timeline.coachAccessExpiryDays' missing — defaulting to 90 days", e);
            expiryDays = 90L;
        }

        if (isCoach) {
            // "Activity" = most recent completed, attended session with this coach, merged across
            // the SLU-row signal and the coach-scoped timeline signal (skillars-12-3 AC1) — a
            // Quick-Complete-only coach with zero SLU rows now reads this correctly too. Report
            // generation and radar assessments do NOT reset the access clock.
            Instant lastSession = coachPlayerLastSessionResolver.resolve(playerId, coachId).lastSessionDate();
            boolean expired = lastSession == null ||
                lastSession.isBefore(Instant.now().minus(expiryDays, ChronoUnit.DAYS));
            if (expired) {
                return new TimelineResponse(true, expiryDays, List.of(), false);
            }
        }

        // skillars-deferred-153 AC4: UNSORTED — findByPlayerIdOrderByOccurredAtDescIdDesc's own ORDER BY
        // already orders this; a Sort carried on the Pageable is appended AFTER that ORDER BY and
        // would silently do nothing.
        Slice<PlayerTimelineEvent> slice = timelineRepository
            .findByPlayerIdOrderByOccurredAtDescIdDesc(playerId, PageRequest.of(page, size));
        List<TimelineEventResponse> events = slice.getContent().stream()
            .map(e -> new TimelineEventResponse(
                e.getId(), e.getEventType(), e.getReferenceId(),
                e.getReferenceModule(), e.getOccurredAt(),
                redactRecapIfNotAuthoringCoach(e, isCoach, coachId)))
            .toList();
        return new TimelineResponse(false, null, events, slice.hasNext());
    }

    // skillars-12-2 AC4: the recap ("whatWeDid"/"whatsNext") written into a SESSION_COMPLETED
    // event's metadata is visible only to the coach who authored it — stripped for every other
    // viewer (another coach, or the player/parent). metadata is a nullable JSONB column, and the
    // existing PlayerTimelineResourceIT seeding helper never sets one at all, so this method must
    // not assume it is non-null.
    private Map<String, Object> redactRecapIfNotAuthoringCoach(PlayerTimelineEvent event, boolean isCoach, UUID coachId) {
        Map<String, Object> metadata = event.getMetadata();
        if (metadata == null || event.getEventType() != PlayerTimelineEventType.SESSION_COMPLETED) {
            return metadata;
        }
        boolean isAuthoringCoach = isCoach && coachId != null
            && coachId.toString().equals(metadata.get(TimelineEventListener.METADATA_KEY_COACH_ID));
        if (isAuthoringCoach) {
            return metadata;
        }
        Map<String, Object> redacted = new HashMap<>(metadata);
        redacted.remove(TimelineEventListener.METADATA_KEY_WHAT_WE_DID);
        redacted.remove(TimelineEventListener.METADATA_KEY_WHATS_NEXT);
        redacted.remove(TimelineEventListener.METADATA_KEY_WHAT_WENT_WELL);
        return redacted;
    }
}
