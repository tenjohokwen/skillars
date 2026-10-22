package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.development.contract.PlayerTimelineEventType;
import com.softropic.skillars.platform.development.contract.TimelineResponse;
import com.softropic.skillars.platform.development.repo.PlayerTimelineEvent;
import com.softropic.skillars.platform.development.repo.PlayerTimelineRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
import static org.mockito.Mockito.when;

/** skillars-12-8 AC2: ratings are authoring-coach only; drills are visible to everyone. */
@ExtendWith(MockitoExtension.class)
class TimelineQueryServiceTest {

    @Mock private PlayerTimelineRepository timelineRepository;
    @Mock private ConfigService configService;
    @Mock private CoachPlayerLastSessionResolver coachPlayerLastSessionResolver;
    @InjectMocks private TimelineQueryService service;

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
        when(configService.getBoundedLong(any(), anyLong(), anyLong())).thenReturn(90L);
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
        when(configService.getBoundedLong(any(), anyLong(), anyLong())).thenReturn(90L);
        when(coachPlayerLastSessionResolver.resolve(anyLong(), any()))
            .thenReturn(new CoachPlayerLastSessionResolver.LastCoachSessionSignal(Instant.now(), null));
        assertThat(metadataFor(true, authoringCoach)).containsKeys(
            TimelineEventListener.METADATA_KEY_DRILLS, TimelineEventListener.METADATA_KEY_EFFORT_RATING,
            TimelineEventListener.METADATA_KEY_FOCUS_RATING, TimelineEventListener.METADATA_KEY_TECHNIQUE_RATING,
            TimelineEventListener.METADATA_KEY_WHAT_WE_DID);
    }
}
