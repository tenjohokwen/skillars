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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(PlaybackServiceIT.class);

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

    /**
     * Measurement only — NOT a gate; see skillars-deferred-89 AC5. A hard millisecond wall-clock p99
     * bound inside a merge-gating IT is structurally flaky (JIT / GC / Testcontainers / CI-host
     * noise) even after skillars-deferred-23 AC1 fixed the percentile-index and warmup bugs. This
     * runs the 100 iterations and logs the p50/p95/p99 so the numbers still appear in CI output, but
     * only keeps a very loose pathology ceiling (a multi-second p99 means something is genuinely
     * broken, not jitter). The correctness of {@code authorizePlayback} is covered by the other
     * cases in this class. ({@code @Tag}-exclusion is not an option — {@code pom.xml} has no
     * {@code excludedGroups}/{@code <groups>} mechanism.)
     */
    @Test
    void authorizePlayback_performance_measuresLatencyDistribution() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE);
        int warmupIterations = 20;
        int iterations = 100;
        long[] latencies = new long[iterations];

        for (int i = 0; i < warmupIterations; i++) {
            playbackService.authorizePlayback(video.getId(), "warmup-viewer-" + i);
        }

        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            playbackService.authorizePlayback(video.getId(), "perf-viewer-" + i);
            latencies[i] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        }

        Arrays.sort(latencies);
        long p50 = latencies[nearestRankIndex(iterations, 50)];
        long p95 = latencies[nearestRankIndex(iterations, 95)];
        long p99 = latencies[nearestRankIndex(iterations, 99)];
        log.info("authorizePlayback latency over {} iterations (measurement only, not a gate): "
            + "p50={}ms p95={}ms p99={}ms", iterations, p50, p95, p99);

        // Loose pathology ceiling only — catches a real regression (something O(n) per call, a lock,
        // a missing index), not CI jitter.
        assertThat(p99).as("p99 latency %dms indicates a genuine pathology, not jitter", p99)
            .isLessThan(5_000L);
    }

    // Nearest-rank percentile via integer ceiling division (avoids floating-point rounding pitfalls
    // in count * p/100): rank = ceil(count * p / 100), 0-based index = rank - 1.
    private static int nearestRankIndex(int count, int percentile) {
        return (count * percentile + 99) / 100 - 1;
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
