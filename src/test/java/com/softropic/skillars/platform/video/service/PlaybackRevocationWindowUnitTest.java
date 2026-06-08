package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.PlaybackTokenClaims;
import com.softropic.skillars.infrastructure.video.SignedPlaybackUrl;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.config.VideoProperties;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.repo.PlaybackToken;
import com.softropic.skillars.platform.video.repo.PlaybackTokenRepository;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaybackRevocationWindowUnitTest {

    @Mock VideoRepository videoRepository;
    @Mock PlaybackTokenRepository playbackTokenRepository;
    @Mock VideoProviderAdapter videoProviderAdapter;
    @Mock VideoMetrics videoMetrics;

    PlaybackService playbackService;

    @BeforeEach
    void setUp() {
        VideoProperties properties = new VideoProperties();
        // Zero revocation window — check must be skipped entirely
        properties.getPlayback().setRevocationWindowHours(0);
        // Valid signing secret (Base64 of 32+ bytes)
        properties.getPlayback().setSigningSecret("dGVzdC1wbGF5YmFjay1zaWduaW5nLXNlY3JldC0zMi1ieXRlcyEh");

        playbackService = new PlaybackService(videoRepository, playbackTokenRepository, videoProviderAdapter, properties, videoMetrics);
    }

    @Test
    void authorizePlayback_zeroRevocationWindow_skipsCheckAndIssuesToken() {
        UUID videoId = UUID.randomUUID();
        String viewerId = "viewer-zero-window";

        Video video = new Video();
        video.setOperationalState(OperationalState.READY);
        video.setAccessState(AccessState.ACTIVE);
        video.setProviderAssetId("bunny-asset-zero-window");

        PlaybackToken savedToken = new PlaybackToken();
        savedToken.setId(UUID.randomUUID());
        savedToken.setVideoId(videoId);
        savedToken.setViewerId(viewerId);
        savedToken.setExpiresAt(Instant.now().plusSeconds(900));

        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));
        when(videoProviderAdapter.generatePlaybackUrl(anyString(), any(PlaybackTokenClaims.class)))
            .thenReturn(new SignedPlaybackUrl("https://cdn/playlist.m3u8?token=test", Instant.now().plusSeconds(900)));
        when(playbackTokenRepository.save(any(PlaybackToken.class))).thenReturn(savedToken);

        var response = playbackService.authorizePlayback(videoId, viewerId);

        assertThat(response.token()).isNotBlank();
        assertThat(response.playbackUrl()).contains("playlist.m3u8");

        // hasRecentRevocation must never be called when window is zero
        verify(playbackTokenRepository, never()).hasRecentRevocation(anyString(), any(Instant.class));
    }
}
