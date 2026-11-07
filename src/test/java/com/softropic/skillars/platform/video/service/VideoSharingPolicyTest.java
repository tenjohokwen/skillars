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

    // The batch form (one scan query for a list): only a PASSED VIDEOINTEL scan shares a video.
    @Test
    void shareable_keepsOnlyVideosWithAPassedScannerScan_inOneQuery() {
        Video other = new Video();
        other.setId(UUID.randomUUID());
        VideoModerationScan passed = mock(VideoModerationScan.class);
        when(passed.getVideoId()).thenReturn(video.getId());
        when(passed.getLayer()).thenReturn("VIDEOINTEL");
        VideoModerationScan otherLayer = mock(VideoModerationScan.class);
        when(otherLayer.getVideoId()).thenReturn(other.getId());
        when(otherLayer.getLayer()).thenReturn("ARACHNID");
        when(scans.findByVideoIdInAndOutcome(java.util.List.of(video.getId(), other.getId()), "PASSED"))
            .thenReturn(java.util.List.of(passed, otherLayer));

        assertThat(policy.shareable(java.util.List.of(video, other))).containsExactly(video);
        org.mockito.Mockito.verify(scans, org.mockito.Mockito.never()).findByVideoIdAndLayer(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shareable_gateSwitchedOff_keepsEverythingWithoutQuerying() {
        properties.getModeration().setRequiresRealScanner(false);
        assertThat(policy.shareable(java.util.List.of(video))).containsExactly(video);
        org.mockito.Mockito.verifyNoInteractions(scans);
    }
}
