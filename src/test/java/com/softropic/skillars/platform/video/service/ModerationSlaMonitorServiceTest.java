package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ModerationSlaMonitorServiceTest {

    @Mock VideoRepository videoRepository;
    @Mock VideoLifecycleService videoLifecycleService;
    @Mock ConfigService configService;
    // skillars-deferred-92 AC5: the service no longer publishes directly — both intents go
    // onto the durable outbox, inside the same transaction as the state change they describe.
    @Mock ModerationOutboxSupport moderationOutboxSupport;
    @Mock TransactionTemplate transactionTemplate;

    @InjectMocks
    ModerationSlaMonitorService service;

    @BeforeEach
    void setUp() {
        // @PostConstruct is not called by Mockito — wire requiresNewTemplate to the same mock
        // so calls inside detectSlaViolations() behave identically to the outer transactionTemplate
        ReflectionTestUtils.setField(service, "requiresNewTemplate", transactionTemplate);
        lenient().when(configService.getLong("platform.moderation_sla_minutes")).thenReturn(30L);
        lenient().when(configService.getLong("platform.moderation_max_retries")).thenReturn(5L);
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
        lenient().when(videoRepository.findById(any())).thenAnswer(inv -> {
            // Return the video from the list that matches the ID — captured in each test
            return Optional.empty();
        });
    }

    @Test
    void noStuckVideos_doesNothing() {
        when(videoRepository.findScanningOlderThan(any(), any(), anyInt())).thenReturn(List.of());

        service.detectSlaViolations();

        verify(moderationOutboxSupport, never()).enqueueRetry(any(), any());
        verify(moderationOutboxSupport, never()).enqueueAdminAlert(any(), any(), any(), any(), anyBoolean());
        verify(videoLifecycleService, never()).transitionOperationalState(any(), any());
    }

    @Test
    void stuckVideo_belowMaxRetries_publishesRetryEvent() {
        Video video = stuckVideo(3);
        when(videoRepository.findScanningOlderThan(any(), any(), anyInt())).thenReturn(List.of(video));
        when(videoRepository.findById(video.getId())).thenReturn(Optional.of(video));
        when(videoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.detectSlaViolations();

        verify(moderationOutboxSupport).enqueueRetry(video.getId(), video.getOwnerId());
        verify(moderationOutboxSupport, never()).enqueueAdminAlert(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void stuckVideo_atMaxRetries_transitionsToFailed_andAlertsAdmin() {
        Video video = stuckVideo(5);
        when(videoRepository.findScanningOlderThan(any(), any(), anyInt())).thenReturn(List.of(video));

        service.detectSlaViolations();

        verify(videoLifecycleService).transitionOperationalState(video.getId(), OperationalState.FAILED);
        ArgumentCaptor<Boolean> urgent = ArgumentCaptor.forClass(Boolean.class);
        verify(moderationOutboxSupport).enqueueAdminAlert(
            eq(video.getId()), eq(video.getOwnerId()), any(), any(), urgent.capture());
        assertThat(urgent.getValue()).isTrue();
        verify(moderationOutboxSupport, never()).enqueueRetry(any(), any());
    }

    @Test
    void stuckVideo_belowMaxRetries_incrementsRetryCount() {
        Video video = stuckVideo(2);
        when(videoRepository.findScanningOlderThan(any(), any(), anyInt())).thenReturn(List.of(video));
        when(videoRepository.findById(video.getId())).thenReturn(Optional.of(video));
        when(videoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.detectSlaViolations();

        assertThat(video.getModerationRetryCount()).isEqualTo(3);
        verify(videoRepository).save(video);
    }

    @Test
    void multipleStuckVideos_mixedRetryCount_handlesEachCorrectly() {
        Video belowLimit = stuckVideo(1);
        Video atLimit = stuckVideo(5);
        when(videoRepository.findScanningOlderThan(any(), any(), anyInt())).thenReturn(List.of(belowLimit, atLimit));
        when(videoRepository.findById(belowLimit.getId())).thenReturn(Optional.of(belowLimit));
        when(videoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.detectSlaViolations();

        verify(videoLifecycleService).transitionOperationalState(atLimit.getId(), OperationalState.FAILED);
        verify(videoLifecycleService, never()).transitionOperationalState(eq(belowLimit.getId()), any());

        verify(moderationOutboxSupport).enqueueRetry(belowLimit.getId(), belowLimit.getOwnerId());
        verify(moderationOutboxSupport).enqueueAdminAlert(
            eq(atLimit.getId()), eq(atLimit.getOwnerId()), any(), any(), eq(true));
    }

    @Test
    void oneVideoThrowsException_nextVideoStillProcesses() {
        Video video1 = stuckVideo(3);
        Video video2 = stuckVideo(4);
        when(videoRepository.findScanningOlderThan(any(), any(), anyInt())).thenReturn(List.of(video1, video2));
        when(videoRepository.findById(video1.getId())).thenThrow(new RuntimeException("DB connection failed"));
        when(videoRepository.findById(video2.getId())).thenReturn(Optional.of(video2));
        when(videoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.detectSlaViolations();

        verify(moderationOutboxSupport).enqueueRetry(video2.getId(), video2.getOwnerId());
        verify(moderationOutboxSupport, never()).enqueueRetry(video1.getId(), video1.getOwnerId());
    }

    private Video stuckVideo(int retryCount) {
        Video v = new Video();
        v.setOwnerId("owner@example.com");
        v.setOperationalState(OperationalState.SCANNING);
        v.setScanningStartedAt(Instant.now().minusSeconds(3600));
        v.setModerationRetryCount(retryCount);
        // Set ID via reflection since JPA @GeneratedValue runs only during persist
        try {
            var field = Video.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(v, UUID.randomUUID());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return v;
    }
}
