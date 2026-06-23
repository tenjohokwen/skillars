package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.BaseVideoIT;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.LifecycleTrigger;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.Visibility;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoDeletionLog;
import com.softropic.skillars.platform.video.repo.VideoDeletionLogRepository;
import com.softropic.skillars.platform.video.repo.VideoDeletionOutbox;
import com.softropic.skillars.platform.video.repo.VideoDeletionOutboxRepository;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

class VideoDeletionOutboxProcessorIT extends BaseVideoIT {

    @MockitoBean VideoProviderAdapter videoProviderAdapter;

    @Autowired VideoDeletionOutboxProcessor processor;
    @Autowired VideoRepository videoRepository;
    @Autowired VideoDeletionOutboxRepository outboxRepository;
    @Autowired VideoDeletionLogRepository deletionLogRepository;
    @Autowired TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        deletionLogRepository.deleteAll();
        outboxRepository.deleteAll();
        videoRepository.deleteAll();
    }

    @Test
    void process_successfulBunnyDelete_completesRowAndNullsProviderAssetId() {
        Video video = seedPurgedVideo("asset-to-delete");
        VideoDeletionOutbox row = seedPendingOutboxRow(video.getId(), "asset-to-delete");
        doNothing().when(videoProviderAdapter).deleteAsset(eq("asset-to-delete"));

        processor.process();

        VideoDeletionOutbox updated = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("COMPLETED");

        Video updatedVideo = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updatedVideo.getProviderAssetId()).isNull();

        List<VideoDeletionLog> logs = deletionLogRepository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getVideoId()).isEqualTo(video.getId());
        assertThat(logs.get(0).getBunnyVideoId()).isEqualTo("asset-to-delete");
    }

    @Test
    void process_nullBunnyVideoId_shortCircuitsWithoutApiCall() {
        Video video = seedPurgedVideo(null);
        VideoDeletionOutbox row = seedPendingOutboxRow(video.getId(), null);

        processor.process();

        VideoDeletionOutbox updated = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("COMPLETED");
        assertThat(deletionLogRepository.findAll()).hasSize(1);
    }

    @Test
    void process_bunnyDeleteFails_incrementsAttemptsAndRetries() {
        Video video = seedPurgedVideo("asset-fail");
        VideoDeletionOutbox row = seedPendingOutboxRow(video.getId(), "asset-fail");
        doThrow(new RuntimeException("Bunny 503")).when(videoProviderAdapter).deleteAsset(eq("asset-fail"));

        processor.process();

        VideoDeletionOutbox updated = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(updated.getAttempts()).isEqualTo(1);
        assertThat(updated.getStatus()).isEqualTo("PENDING");
        assertThat(updated.getLastError()).contains("Bunny 503");
        assertThat(updated.getNextRetryAt()).isAfter(Instant.now());
    }

    @Test
    void process_maxAttemptsExceeded_rowBecomesDeadLetter() {
        Video video = seedPurgedVideo("asset-dead");
        VideoDeletionOutbox row = seedPendingOutboxRow(video.getId(), "asset-dead");
        // Set attempts to max - 1 so next failure triggers DEAD
        transactionTemplate.execute(status -> {
            VideoDeletionOutbox loaded = outboxRepository.findById(row.getId()).orElseThrow();
            loaded.setAttempts(4); // max is 5 from config seed
            outboxRepository.save(loaded);
            return null;
        });
        doThrow(new RuntimeException("Bunny permanently down")).when(videoProviderAdapter).deleteAsset(eq("asset-dead"));

        processor.process();

        VideoDeletionOutbox updated = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("DEAD");
        assertThat(updated.getAttempts()).isEqualTo(5);
    }

    @Test
    void process_failThenSucceed_completesOnRetry() {
        Video video = seedPurgedVideo("asset-retry");
        VideoDeletionOutbox row = seedPendingOutboxRow(video.getId(), "asset-retry");

        // First drain: Bunny fails
        doThrow(new RuntimeException("Bunny 503")).when(videoProviderAdapter).deleteAsset(eq("asset-retry"));
        processor.process();

        VideoDeletionOutbox afterFirst = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(afterFirst.getAttempts()).isEqualTo(1);
        assertThat(afterFirst.getStatus()).isEqualTo("PENDING");

        // Reset next_retry_at so the row is eligible on the next drain
        transactionTemplate.execute(status -> {
            VideoDeletionOutbox loaded = outboxRepository.findById(row.getId()).orElseThrow();
            loaded.setNextRetryAt(Instant.now().minusSeconds(1));
            outboxRepository.save(loaded);
            return null;
        });

        // Second drain: Bunny succeeds
        doNothing().when(videoProviderAdapter).deleteAsset(eq("asset-retry"));
        processor.process();

        VideoDeletionOutbox afterSecond = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(afterSecond.getStatus()).isEqualTo("COMPLETED");
    }

    private Video seedPurgedVideo(String providerAssetId) {
        Video v = new Video();
        v.setOwnerId("test-owner");
        v.setProvider("bunny");
        v.setProviderAssetId(providerAssetId);
        v.setTitle("outbox-test.mp4");
        v.setOperationalState(OperationalState.PURGED);
        v.setAccessState(AccessState.ACTIVE);
        v.setVisibility(Visibility.PRIVATE);
        return videoRepository.save(v);
    }

    private VideoDeletionOutbox seedPendingOutboxRow(UUID videoId, String bunnyVideoId) {
        VideoDeletionOutbox row = new VideoDeletionOutbox();
        row.setVideoId(videoId);
        row.setBunnyVideoId(bunnyVideoId);
        row.setStatus("PENDING");
        row.setAttempts(0);
        row.setTriggeredBy(LifecycleTrigger.USER_DELETION);
        return outboxRepository.save(row);
    }
}
