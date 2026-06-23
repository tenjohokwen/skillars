package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.BaseVideoIT;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.LifecycleTrigger;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.Visibility;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoLifecycleLog;
import com.softropic.skillars.platform.video.repo.VideoLifecycleLogRepository;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VideoLifecycleLogIT extends BaseVideoIT {

    @Autowired VideoRepository videoRepository;
    @Autowired VideoLifecycleLogRepository videoLifecycleLogRepository;
    @Autowired VideoLifecycleService videoLifecycleService;

    @BeforeEach
    void setUp() {
        videoLifecycleLogRepository.deleteAll();
        videoRepository.deleteAll();
    }

    @Test
    void blockForSubscriptionExpiry_setsLifecycleLockedAtAndCanBeLogged() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE);

        transactionTemplate.execute(s -> {
            videoLifecycleService.blockForSubscriptionExpiry(video.getId(), Instant.now());
            return null;
        });

        VideoLifecycleLog log = new VideoLifecycleLog();
        log.setVideoId(video.getId());
        log.setFromState("ACTIVE");
        log.setToState("BLOCKED");
        log.setTriggeredBy(LifecycleTrigger.SUBSCRIPTION_EXPIRY);
        videoLifecycleLogRepository.save(log);

        List<VideoLifecycleLog> logs = videoLifecycleLogRepository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getFromState()).isEqualTo("ACTIVE");
        assertThat(logs.get(0).getToState()).isEqualTo("BLOCKED");
        assertThat(logs.get(0).getTriggeredBy()).isEqualTo(LifecycleTrigger.SUBSCRIPTION_EXPIRY);
        assertThat(logs.get(0).getTransitionedAt()).isNotNull();

        Video reloaded = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(reloaded.getAccessState()).isEqualTo(AccessState.BLOCKED);
        assertThat(reloaded.getLifecycleLockedAt()).isNotNull();
    }

    @Test
    void archiveForLifecycle_setsArchivedAtAndCanBeLogged() {
        Video video = seedVideo(OperationalState.READY, AccessState.BLOCKED);
        transactionTemplate.execute(s -> {
            video.setLifecycleLockedAt(Instant.now().minusSeconds(3600));
            videoRepository.save(video);
            return null;
        });

        transactionTemplate.execute(s -> {
            videoLifecycleService.archiveForLifecycle(video.getId());
            return null;
        });

        VideoLifecycleLog log = new VideoLifecycleLog();
        log.setVideoId(video.getId());
        log.setFromState("BLOCKED");
        log.setToState("ARCHIVED");
        log.setTriggeredBy(LifecycleTrigger.SYSTEM);
        videoLifecycleLogRepository.save(log);

        Video reloaded = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(reloaded.getAccessState()).isEqualTo(AccessState.ARCHIVED);
        assertThat(reloaded.getArchivedAt()).isNotNull();

        List<VideoLifecycleLog> logs = videoLifecycleLogRepository.findAll();
        assertThat(logs.get(0).getTriggeredBy()).isEqualTo(LifecycleTrigger.SYSTEM);
    }

    @Test
    void markPurged_setsOperationalStateDeletedAndZerosStorageBytesAndCanBeLogged() {
        Video video = seedVideo(OperationalState.READY, AccessState.ARCHIVED);
        transactionTemplate.execute(s -> {
            video.setStorageBytes(2048L);
            videoRepository.save(video);
            return null;
        });

        long released = transactionTemplate.execute(s ->
            videoLifecycleService.markPurged(video.getId()));

        assertThat(released).isEqualTo(2048L);

        VideoLifecycleLog log = new VideoLifecycleLog();
        log.setVideoId(video.getId());
        log.setFromState("ARCHIVED");
        log.setToState("DELETED");
        log.setTriggeredBy(LifecycleTrigger.SYSTEM);
        videoLifecycleLogRepository.save(log);

        Video reloaded = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(reloaded.getOperationalState()).isEqualTo(OperationalState.DELETED);
        assertThat(reloaded.getStorageBytes()).isZero();
    }

    private Video seedVideo(OperationalState opState, AccessState accessState) {
        Video video = new Video();
        video.setOwnerId("owner-log-test");
        video.setProvider("bunny");
        video.setProviderAssetId("provider-log-test");
        video.setTitle("log-test.mp4");
        video.setOperationalState(opState);
        video.setAccessState(accessState);
        video.setVisibility(Visibility.PRIVATE);
        if (accessState == AccessState.BLOCKED) {
            video.setLifecycleLockedAt(Instant.now());
        }
        return videoRepository.save(video);
    }
}
