package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.contract.PlayerTimelineEventType;
import com.softropic.skillars.platform.development.contract.TimelineEventResponse;
import com.softropic.skillars.platform.development.contract.TimelineResponse;
import com.softropic.skillars.platform.development.repo.PlayerTimelineEvent;
import com.softropic.skillars.platform.development.repo.PlayerTimelineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimelineQueryService {

    private final PlayerTimelineRepository timelineRepository;
    private final CoachAccessWindow coachAccessWindow;
    private final CoachPlayerLastSessionResolver coachPlayerLastSessionResolver;

    public TimelineResponse getTimeline(Long playerId, boolean isCoach, UUID coachId, int page, int size) {
        if (isCoach) {
            // "Activity" = the latest completed, attended session with this coach (END time, merged across
            // the SLU-row signal and the coach-scoped timeline signal -- skillars-12-3 AC1 / skillars-12-10
            // AC4). skillars-12-10 AC2/AC5: the shared window rule, attended-session clock only -- NO
            // open-booking arm here (a booking must not earn history access), null reads as expired.
            // Report generation and radar assessments do NOT reset the access clock.
            long expiryDays = coachAccessWindow.windowDays();
            Instant lastSession = coachPlayerLastSessionResolver.resolve(playerId, coachId).lastSessionDate();
            if (!CoachAccessWindow.isOpen(lastSession, coachAccessWindow.now(), expiryDays)) {
                return new TimelineResponse(true, expiryDays, List.of(), false);
            }
        }

        // skillars-deferred-153 AC4: UNSORTED — findByPlayerIdOrderByOccurredAtDescIdDesc's own ORDER BY
        // already orders this; a Sort carried on the Pageable is appended AFTER that ORDER BY and
        // would silently do nothing.
        Slice<PlayerTimelineEvent> slice = timelineRepository
            .findByPlayerIdOrderByOccurredAtDescIdDesc(playerId, PageRequest.of(page, size));
        List<TimelineEventResponse> events = slice.getContent().stream()
            .map(e -> toResponse(e, isCoach, coachId))
            .toList();
        return new TimelineResponse(false, null, events, slice.hasNext());
    }

    // skillars-12-10 AC9 (G25): metadata is an ALLOWLIST per event type, so a new free-text key cannot leak by
    // default. Rows (see the story's table): the authoring coach (metadata.coachId == viewer) sees everything on
    // SESSION_COMPLETED / HOMEWORK_ASSIGNED; everyone else sees only the listed keys. `drills` is plan content and
    // stays visible to every viewer (owner decision 2026-10-23). `coachId` is never exposed to a non-author.
    // PERFORMANCE_REPORT carries no coachId: coaches see nothing, the player/parent (who were emailed the
    // report) see coachName + reportId. Unknown/stub event types expose nothing. metadata is nullable JSONB.
    private static final Map<PlayerTimelineEventType, Set<String>> VIEWER_ALLOWLIST = Map.of(
        PlayerTimelineEventType.SESSION_COMPLETED, Set.of(TimelineEventListener.METADATA_KEY_DRILLS),
        PlayerTimelineEventType.HOMEWORK_ASSIGNED, Set.of(TimelineEventListener.METADATA_KEY_DRILLS),
        PlayerTimelineEventType.RADAR_ASSESSMENT, Set.of("skillCodes"));
    private static final Set<String> REPORT_KEYS_FOR_PLAYER_OR_PARENT = Set.of("coachName", "reportId");

    // skillars-deferred-159 AC4: authorship is decided once per row, ahead of any null-metadata early return,
    // and gates BOTH metadata and referenceId. A coach viewer gets referenceId only as the authoring coach
    // (allowlist form: stub/unknown types and PERFORMANCE_REPORT expose nothing to a coach). A row with null
    // metadata is never "authored", so its referenceId is hidden from every coach.
    private TimelineEventResponse toResponse(PlayerTimelineEvent e, boolean isCoach, UUID coachId) {
        Map<String, Object> metadata = e.getMetadata();
        PlayerTimelineEventType type = e.getEventType();
        boolean authorable = type == PlayerTimelineEventType.SESSION_COMPLETED
            || type == PlayerTimelineEventType.HOMEWORK_ASSIGNED;
        boolean isAuthoringCoach = authorable && isCoach && coachId != null && metadata != null
            && coachId.toString().equals(metadata.get(TimelineEventListener.METADATA_KEY_COACH_ID));
        UUID referenceId = isCoach && !isAuthoringCoach ? null : e.getReferenceId();
        return new TimelineEventResponse(
            e.getId(), type, referenceId, e.getReferenceModule(), e.getOccurredAt(),
            redactCoachPrivateFields(metadata, type, isCoach, isAuthoringCoach));
    }

    private Map<String, Object> redactCoachPrivateFields(Map<String, Object> metadata, PlayerTimelineEventType type,
                                                         boolean isCoach, boolean isAuthoringCoach) {
        if (metadata == null) {
            return null;
        }
        if (isAuthoringCoach) {
            return metadata;
        }
        Set<String> allowed = type == PlayerTimelineEventType.PERFORMANCE_REPORT
            ? (isCoach ? Set.of() : REPORT_KEYS_FOR_PLAYER_OR_PARENT)
            : VIEWER_ALLOWLIST.getOrDefault(type, Set.of());
        Map<String, Object> visible = new HashMap<>();
        for (String key : allowed) {
            if (metadata.containsKey(key)) {
                visible.put(key, metadata.get(key));
            }
        }
        return visible;
    }
}
