package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.config.VideoProperties;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoModerationScan;
import com.softropic.skillars.platform.video.repo.VideoModerationScanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VideoSharingPolicyTest {

    private final VideoProperties properties = new VideoProperties();
    private final VideoModerationScanRepository scans = mock(VideoModerationScanRepository.class);
    private final VideoSharingPolicy policy = new VideoSharingPolicy(properties, scans);
    private final Video video = new Video();

    @BeforeEach
    void setUp() {
        video.setId(UUID.randomUUID());
    }

    private void scanOutcome(String outcome) {
        VideoModerationScan scan = mock(VideoModerationScan.class);
        when(scan.getOutcome()).thenReturn(outcome);
        when(scans.findByVideoIdAndLayer(video.getId(), "VIDEOINTEL")).thenReturn(Optional.of(scan));
    }

    @Test
    void defaultsToRequiringARealScanner() {
        assertThat(properties.getModeration().isRequiresRealScanner()).isTrue();
    }

    @Test
    void noScanRecord_orAnythingButPassed_staysPrivate() {
        assertThat(policy.maySharePastOwner(video)).isFalse();
        for (String outcome : new String[] {"SKIPPED", "FAILED", "FLAGGED"}) {
            scanOutcome(outcome);
            assertThat(policy.maySharePastOwner(video)).as(outcome).isFalse();
        }
    }

    @Test
    void passedByTheScannerLayer_mayBeShared() {
        scanOutcome("PASSED");
        assertThat(policy.maySharePastOwner(video)).isTrue();
    }

    @Test
    void gateSwitchedOff_everythingMayBeShared() {
        properties.getModeration().setRequiresRealScanner(false);
        assertThat(policy.maySharePastOwner(video)).isTrue();
    }
}
