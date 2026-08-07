package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.PlaybackTokenClaims;
import com.softropic.skillars.infrastructure.video.SignedPlaybackUrl;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.BaseVideoIT;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.PlaybackAuthorizationResponse;
import com.softropic.skillars.platform.video.contract.Visibility;
import com.softropic.skillars.platform.video.contract.exception.PlaybackDeniedException;
import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
import com.softropic.skillars.platform.video.repo.PlaybackToken;
import com.softropic.skillars.platform.video.repo.PlaybackTokenRepository;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class PlaybackServiceIT extends BaseVideoIT {

    @MockitoBean
    VideoProviderAdapter videoProviderAdapter;

    @Autowired
    PlaybackService playbackService;

    @Autowired
    VideoRepository videoRepository;

    @Autowired
    PlaybackTokenRepository playbackTokenRepository;

    @Value("${app.video.playback.signing-secret}")
    String signingSecret;

    @BeforeEach
    void setUp() {
        playbackTokenRepository.deleteAll();
        videoRepository.deleteAll();
        when(videoProviderAdapter.generatePlaybackUrl(anyString(), any(PlaybackTokenClaims.class)))
            .thenReturn(new SignedPlaybackUrl("https://bunny-cdn/asset-id/playlist.m3u8?token=test", Instant.now().plusSeconds(900)));
    }

    @Test
    void authorizePlayback_happyPath_returnsTokenAndUrl() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE);

        PlaybackAuthorizationResponse response = playbackService.authorizePlayback(video.getId(), "viewer-1");

        assertThat(response.token()).isNotBlank();
        assertThat(response.playbackUrl()).contains("playlist.m3u8");
        assertThat(response.expiresAt()).isAfter(Instant.now());

        List<PlaybackToken> tokens = playbackTokenRepository.findAll();
        assertThat(tokens).hasSize(1);
        PlaybackToken saved = tokens.get(0);
        assertThat(saved.getVideoId()).isEqualTo(video.getId());
        assertThat(saved.getViewerId()).isEqualTo("viewer-1");
        assertThat(saved.getRevokedAt()).isNull();

        SecretKey key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(signingSecret));
        Claims claims = Jwts.parser().verifyWith(key).build()
            .parseSignedClaims(response.token()).getPayload();
        assertThat(claims.getId()).isEqualTo(saved.getId().toString());
        assertThat(claims.getSubject()).isEqualTo("viewer-1");
        assertThat(claims.get("vid", String.class)).isEqualTo(video.getId().toString());
        assertThat(claims.getExpiration()).isAfter(java.util.Date.from(Instant.now()));
    }

    @Test
    void authorizePlayback_processingVideo_throwsPlaybackDenied() {
        Video video = seedVideo(OperationalState.PROCESSING, AccessState.ACTIVE);

        assertThatThrownBy(() -> playbackService.authorizePlayback(video.getId(), "viewer-2"))
            .isInstanceOf(PlaybackDeniedException.class);
    }

    @Test
    void authorizePlayback_videoNotFound_throwsVideoNotFound() {
        assertThatThrownBy(() -> playbackService.authorizePlayback(UUID.randomUUID(), "viewer-3"))
            .isInstanceOf(VideoNotFoundException.class);
    }

    @Test
    void authorizePlayback_performance_p99Under200ms() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE);
        int iterations = 100;
        long[] latencies = new long[iterations];

        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            playbackService.authorizePlayback(video.getId(), "perf-viewer-" + i);
            latencies[i] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        }

        Arrays.sort(latencies);
        long p99 = latencies[(int) (iterations * 0.99)];
        assertThat(p99).as("p99 latency must be < 200ms but was %dms", p99).isLessThan(200L);
    }

    private Video seedVideo(OperationalState opState, AccessState accessState) {
        Video video = new Video();
        video.setOwnerId("owner-playback");
        video.setProvider("bunny");
        video.setProviderAssetId("bunny-asset-id-123");
        video.setTitle("test-video.mp4");
        video.setOperationalState(opState);
        video.setAccessState(accessState);
        video.setVisibility(Visibility.PRIVATE);
        return videoRepository.save(video);
    }
}
