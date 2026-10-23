package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.development.contract.PlayerTimelineEventType;
import com.softropic.skillars.platform.development.contract.TimelineResponse;
import com.softropic.skillars.platform.development.repo.PlayerTimelineEvent;
import com.softropic.skillars.platform.development.repo.PlayerTimelineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * skillars-12-8 AC2: ratings are authoring-coach only; drills are visible to everyone.
 * skillars-12-10 AC5/AC9: shared access window (attended clock, null = expired) and the metadata allowlist.
 */
@ExtendWith(MockitoExtension.class)
class TimelineQueryServiceTest {

    @Mock private PlayerTimelineRepository timelineRepository;
    @Mock private ConfigService configService;
    @Mock private CoachPlayerLastSessionResolver coachPlayerLastSessionResolver;
    private TimelineQueryService service;

    @BeforeEach
    void setUp() {
        // skillars-12-10: the real shared window rule over a stubbed 90-day config value.
        lenient().when(configService.getBoundedLong(any(), anyLong(), anyLong(), anyLong())).thenReturn(90L);
        service = new TimelineQueryService(
            timelineRepository, new CoachAccessWindow(configService), coachPlayerLastSessionResolver);
    }

    private final UUID authoringCoach = UUID.randomUUID();

    private void seedSessionCompleted() {
        Map<String, Object> md = new HashMap<>();
        md.put(TimelineEventListener.METADATA_KEY_COACH_ID, authoringCoach.toString());
        md.put(TimelineEventListener.METADATA_KEY_WHAT_WE_DID, "x");
        md.put(TimelineEventListener.METADATA_KEY_DRILLS, List.of(Map.of("name", "Rondo")));
        md.put(TimelineEventListener.METADATA_KEY_EFFORT_RATING, 4);
        md.put(TimelineEventListener.METADATA_KEY_FOCUS_RATING, 3);
        md.put(TimelineEventListener.METADATA_KEY_TECHNIQUE_RATING, 5);
        PlayerTimelineEvent e = new PlayerTimelineEvent();
        e.setId(UUID.randomUUID());
        e.setEventType(PlayerTimelineEventType.SESSION_COMPLETED);
        e.setOccurredAt(Instant.now());
        e.setMetadata(md);
        when(timelineRepository.findByPlayerIdOrderByOccurredAtDescIdDesc(anyLong(), any()))
            .thenReturn(new SliceImpl<>(List.of(e), PageRequest.of(0, 10), false));
    }

    private Map<String, Object> metadataFor(boolean isCoach, UUID coachId) {
        TimelineResponse r = service.getTimeline(1L, isCoach, coachId, 0, 10);
        return r.events().get(0).metadata();
    }

    @Test
    void parentOrPlayer_keepsDrills_losesRatingsAndRecap() {
        seedSessionCompleted();
        assertThat(metadataFor(false, null))
            .containsKey(TimelineEventListener.METADATA_KEY_DRILLS)
            .doesNotContainKeys(TimelineEventListener.METADATA_KEY_EFFORT_RATING,
                TimelineEventListener.METADATA_KEY_FOCUS_RATING, TimelineEventListener.METADATA_KEY_TECHNIQUE_RATING,
                TimelineEventListener.METADATA_KEY_WHAT_WE_DID);
    }

    @Test
    void otherCoach_keepsDrills_losesRatings() {
        seedSessionCompleted();
        when(coachPlayerLastSessionResolver.resolve(anyLong(), any()))
            .thenReturn(new CoachPlayerLastSessionResolver.LastCoachSessionSignal(Instant.now(), null));
        assertThat(metadataFor(true, UUID.randomUUID()))
            .containsKey(TimelineEventListener.METADATA_KEY_DRILLS)
            .doesNotContainKeys(TimelineEventListener.METADATA_KEY_EFFORT_RATING,
                TimelineEventListener.METADATA_KEY_FOCUS_RATING, TimelineEventListener.METADATA_KEY_TECHNIQUE_RATING);
    }

    @Test
    void authoringCoach_seesEverything() {
        seedSessionCompleted();
        when(coachPlayerLastSessionResolver.resolve(anyLong(), any()))
            .thenReturn(new CoachPlayerLastSessionResolver.LastCoachSessionSignal(Instant.now(), null));
        assertThat(metadataFor(true, authoringCoach)).containsKeys(
            TimelineEventListener.METADATA_KEY_DRILLS, TimelineEventListener.METADATA_KEY_EFFORT_RATING,
            TimelineEventListener.METADATA_KEY_FOCUS_RATING, TimelineEventListener.METADATA_KEY_TECHNIQUE_RATING,
            TimelineEventListener.METADATA_KEY_WHAT_WE_DID);
    }

    // ── skillars-12-10 AC5: window ────────────────────────────────────────────────────────────────

    @Test
    void coach_lastSessionOutsideWindow_isExpired() {
        when(coachPlayerLastSessionResolver.resolve(anyLong(), any())).thenReturn(
            new CoachPlayerLastSessionResolver.LastCoachSessionSignal(Instant.now().minusSeconds(91L * 86400), null));
        TimelineResponse r = service.getTimeline(1L, true, UUID.randomUUID(), 0, 10);
        assertThat(r.accessExpired()).isTrue();
        assertThat(r.accessExpiryDays()).isEqualTo(90L);
        assertThat(r.events()).isEmpty();
    }

    @Test
    void coach_lastSessionInsideWindow_isOpen() {
        seedSessionCompleted();
        when(coachPlayerLastSessionResolver.resolve(anyLong(), any())).thenReturn(
            new CoachPlayerLastSessionResolver.LastCoachSessionSignal(Instant.now().minusSeconds(89L * 86400), null));
        assertThat(service.getTimeline(1L, true, authoringCoach, 0, 10).accessExpired()).isFalse();
    }

    @Test
    void coach_noAttendedSession_isExpired_noOpenBookingArm() {
        when(coachPlayerLastSessionResolver.resolve(anyLong(), any())).thenReturn(
            new CoachPlayerLastSessionResolver.LastCoachSessionSignal(null, null));
        assertThat(service.getTimeline(1L, true, UUID.randomUUID(), 0, 10).accessExpired()).isTrue();
    }

    // ── skillars-12-10 AC9: allowlist ─────────────────────────────────────────────────────────────

    private PlayerTimelineEvent event(PlayerTimelineEventType type, Map<String, Object> md) {
        PlayerTimelineEvent e = new PlayerTimelineEvent();
        e.setId(UUID.randomUUID());
        e.setEventType(type);
        e.setOccurredAt(Instant.now());
        e.setMetadata(md);
        when(timelineRepository.findByPlayerIdOrderByOccurredAtDescIdDesc(anyLong(), any()))
            .thenReturn(new SliceImpl<>(List.of(e), PageRequest.of(0, 10), false));
        return e;
    }

    private void coachWithOpenWindow() {
        when(coachPlayerLastSessionResolver.resolve(anyLong(), any())).thenReturn(
            new CoachPlayerLastSessionResolver.LastCoachSessionSignal(Instant.now(), null));
    }

    @Test
    void sessionCompleted_unknownKeyIsDroppedForEveryoneButTheAuthor_andCoachIdNeverLeaks() {
        Map<String, Object> md = new HashMap<>();
        md.put(TimelineEventListener.METADATA_KEY_COACH_ID, authoringCoach.toString());
        md.put(TimelineEventListener.METADATA_KEY_DRILLS, List.of(Map.of("name", "Rondo")));
        md.put("secretNote", "free text");
        event(PlayerTimelineEventType.SESSION_COMPLETED, md);
        coachWithOpenWindow();

        assertThat(metadataFor(false, null)).containsOnlyKeys(TimelineEventListener.METADATA_KEY_DRILLS);
        assertThat(metadataFor(true, UUID.randomUUID())).containsOnlyKeys(TimelineEventListener.METADATA_KEY_DRILLS);
        assertThat(metadataFor(true, authoringCoach)).containsKeys("secretNote", TimelineEventListener.METADATA_KEY_COACH_ID);
    }

    @Test
    void homeworkAssigned_otherViewersSeeOnlyDrills_authorSeesAll() {
        Map<String, Object> md = new HashMap<>();
        md.put(TimelineEventListener.METADATA_KEY_COACH_ID, authoringCoach.toString());
        md.put(TimelineEventListener.METADATA_KEY_DRILLS, List.of(Map.of("name", "Wall pass")));
        md.put("secretNote", "x");
        event(PlayerTimelineEventType.HOMEWORK_ASSIGNED, md);
        coachWithOpenWindow();

        assertThat(metadataFor(false, null)).containsOnlyKeys(TimelineEventListener.METADATA_KEY_DRILLS);
        assertThat(metadataFor(true, UUID.randomUUID())).containsOnlyKeys(TimelineEventListener.METADATA_KEY_DRILLS);
        assertThat(metadataFor(true, authoringCoach)).containsKeys("secretNote", TimelineEventListener.METADATA_KEY_COACH_ID);
    }

    @Test
    void radarAssessment_exposesOnlySkillCodes_toEveryone() {
        event(PlayerTimelineEventType.RADAR_ASSESSMENT, new HashMap<>(Map.of("skillCodes", List.of("PASS"), "secretNote", "x")));
        coachWithOpenWindow();

        assertThat(metadataFor(false, null)).containsOnlyKeys("skillCodes");
        assertThat(metadataFor(true, UUID.randomUUID())).containsOnlyKeys("skillCodes");
    }

    @Test
    void performanceReport_coachSeesNothing_playerOrParentSeesCoachNameAndReportId() {
        event(PlayerTimelineEventType.PERFORMANCE_REPORT,
            new HashMap<>(Map.of("coachName", "C", "reportId", "r1", "secretNote", "x")));
        coachWithOpenWindow();

        assertThat(metadataFor(false, null)).containsOnlyKeys("coachName", "reportId");
        assertThat(metadataFor(true, UUID.randomUUID())).isEmpty();
    }

    @Test
    void stubEventTypes_exposeNothing_andNullMetadataStaysNull() {
        event(PlayerTimelineEventType.MILESTONE_REACHED, new HashMap<>(Map.of("anything", "x")));
        assertThat(metadataFor(false, null)).isEmpty();
        event(PlayerTimelineEventType.SESSION_COMPLETED, null);
        assertThat(metadataFor(false, null)).isNull();
    }

    // ── skillars-deferred-159 AC4: referenceId follows the metadata visibility rule ───────────────

    private UUID referenceIdFor(boolean isCoach, UUID coachId) {
        return service.getTimeline(1L, isCoach, coachId, 0, 10).events().get(0).referenceId();
    }

    private UUID eventWithReference(PlayerTimelineEventType type, Map<String, Object> md) {
        UUID ref = UUID.randomUUID();
        event(type, md).setReferenceId(ref);
        coachWithOpenWindow();
        return ref;
    }

    @Test
    void referenceId_sessionCompletedAndHomework_hiddenFromOtherCoach_visibleToAuthorAndPlayerParent() {
        for (PlayerTimelineEventType type : List.of(
                PlayerTimelineEventType.SESSION_COMPLETED, PlayerTimelineEventType.HOMEWORK_ASSIGNED)) {
            Map<String, Object> md = new HashMap<>(Map.of(
                TimelineEventListener.METADATA_KEY_COACH_ID, authoringCoach.toString()));
            UUID ref = eventWithReference(type, md);

            assertThat(referenceIdFor(true, UUID.randomUUID())).as("other coach, %s", type).isNull();
            assertThat(referenceIdFor(true, authoringCoach)).as("author, %s", type).isEqualTo(ref);
            assertThat(referenceIdFor(false, null)).as("player/parent, %s", type).isEqualTo(ref);
        }
    }

    @Test
    void referenceId_performanceReport_hiddenFromEveryCoach_visibleToPlayerParent() {
        UUID ref = eventWithReference(PlayerTimelineEventType.PERFORMANCE_REPORT,
            new HashMap<>(Map.of("coachName", "C", "reportId", "r1")));

        assertThat(referenceIdFor(true, UUID.randomUUID())).isNull();
        assertThat(referenceIdFor(false, null)).isEqualTo(ref);
    }

    @Test
    void referenceId_nullMetadataRow_hiddenFromEveryCoachButNotFromPlayerParent() {
        UUID ref = eventWithReference(PlayerTimelineEventType.SESSION_COMPLETED, null);

        assertThat(referenceIdFor(true, authoringCoach)).isNull();
        assertThat(referenceIdFor(true, UUID.randomUUID())).isNull();
        assertThat(referenceIdFor(false, null)).isEqualTo(ref);
    }

    @Test
    void referenceId_stubTypes_hiddenFromCoach() {
        eventWithReference(PlayerTimelineEventType.MILESTONE_REACHED, new HashMap<>(Map.of("anything", "x")));
        assertThat(referenceIdFor(true, UUID.randomUUID())).isNull();
    }
}
