package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.session.service.VideoPhysicalDeletionListener;
import com.softropic.skillars.platform.video.BaseVideoIT;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.LifecycleTrigger;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.Visibility;
import com.softropic.skillars.platform.video.contract.event.VideoPurgedEvent;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Story deferred-6, AC1: VideoPurgedEvent must only be observed by consumers AFTER the
 * publishing transaction commits — a rolled-back deleteVideo() must never reach a listener.
 */
class VideoPurgedEventIT extends BaseVideoIT {

    @MockitoBean VideoProviderAdapter videoProviderAdapter;
    @MockitoSpyBean VideoPhysicalDeletionListener physicalDeletionListener;

    @Autowired VideoDeletionService videoDeletionService;
    @Autowired VideoRepository videoRepository;
    @Autowired TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        doNothing().when(videoProviderAdapter).deleteAsset(anyString());
        videoRepository.deleteAll();
    }

    @Test
    void videoPurgedEvent_publishedAfterCommit() {
        Video video = seedVideo("owner-commit-" + java.util.UUID.randomUUID());

        videoDeletionService.deleteVideo(video.getId(), LifecycleTrigger.SYSTEM, true);

        // Listener is @Async — allow it to run, but it must fire since the TX committed
        verify(physicalDeletionListener, timeout(5000))
            .onVideoPurged(argThat((VideoPurgedEvent e) -> e.videoId().equals(video.getId())));
    }

    @Test
    void videoPurgedEvent_rolledBackTransaction_listenerNeverFires() {
        Video video = seedVideo("owner-rollback-" + java.util.UUID.randomUUID());

        transactionTemplate.execute(status -> {
            videoDeletionService.deleteVideo(video.getId(), LifecycleTrigger.SYSTEM, true);
            status.setRollbackOnly();
            return null;
        });

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getOperationalState())
            .isNotEqualTo(OperationalState.PURGED);

        // Give the @Async listener a window in which it would fire if AFTER_COMMIT were broken,
        // then assert it never was.
        await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).untilAsserted(() ->
            verify(physicalDeletionListener, never()).onVideoPurged(any()));
    }

    private Video seedVideo(String ownerId) {
        Video v = new Video();
        v.setOwnerId(ownerId);
        v.setProvider("bunny");
        v.setProviderAssetId("asset-" + ownerId);
        v.setTitle("purged-event-test.mp4");
        v.setOperationalState(OperationalState.READY);
        v.setAccessState(AccessState.ACTIVE);
        v.setVisibility(Visibility.PRIVATE);
        return videoRepository.save(v);
    }
}
