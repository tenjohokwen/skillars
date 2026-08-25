package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.PlaybackTokenClaims;
import com.softropic.skillars.infrastructure.video.SignedPlaybackUrl;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.BaseVideoIT;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.Visibility;
import com.softropic.skillars.platform.video.repo.PlaybackToken;
import com.softropic.skillars.platform.video.repo.PlaybackTokenRepository;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Deferred-64 AC3 / Task 3.3: proves the lock added around {@code authorizePlayback}'s
 * exists-then-charge sequence actually closes the concurrency-race gap skillars-deferred-63 AC3
 * explicitly deferred, using real concurrent threads against the real Postgres row lock — a
 * mocked-repository unit test cannot exercise the race itself, only the code shape around it.
 */
class PlaybackServiceConcurrencyIT extends BaseVideoIT {

    @MockitoBean
    VideoProviderAdapter videoProviderAdapter;

    @Autowired
    PlaybackService playbackService;

    @Autowired
    VideoRepository videoRepository;

    @Autowired
    PlaybackTokenRepository playbackTokenRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        playbackTokenRepository.deleteAll();
        videoRepository.deleteAll();
        when(videoProviderAdapter.generatePlaybackUrl(anyString(), any(PlaybackTokenClaims.class)))
            .thenReturn(new SignedPlaybackUrl("https://bunny-cdn/asset-id/playlist.m3u8?token=test", Instant.now().plusSeconds(900)));
    }

    /**
     * Two genuinely concurrent authorizations of the SAME (viewerId, videoId) pair must charge
     * bandwidth exactly once between them. Before this AC, both requests could pass the unlocked
     * exists-check before either committed its new token row, double-charging the owner. The lock
     * now serializes the second request behind the first's whole transaction (NO_WAIT + bounded
     * retry), so by the time it re-checks, the first request's token is already committed and
     * visible — deduping the second charge exactly as the existing single-threaded dedup test
     * (Deferred-63 AC3) already proves for the non-concurrent case.
     */
    @Test
    void authorizePlayback_twoConcurrentSameViewerAndVideo_chargesBandwidthExactlyOnce() throws Exception {
        long storageBytes = 500_000L;
        String ownerId = "owner-concurrency-1";
        Video video = seedVideo(ownerId, storageBytes);
        ensureQuotaRow(ownerId);
        String viewerId = "viewer-concurrency-1";

        CountDownLatch startTogether = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> failure1 = new AtomicReference<>();
        AtomicReference<Throwable> failure2 = new AtomicReference<>();

        Future<?> call1 = executor.submit(() -> raceAndAuthorize(startTogether, video.getId(), viewerId, failure1));
        Future<?> call2 = executor.submit(() -> raceAndAuthorize(startTogether, video.getId(), viewerId, failure2));

        call1.get(30, TimeUnit.SECONDS);
        call2.get(30, TimeUnit.SECONDS);
        executor.shutdown();

        if (failure1.get() != null) {
            throw new AssertionError("First concurrent authorization failed unexpectedly", failure1.get());
        }
        if (failure2.get() != null) {
            throw new AssertionError("Second concurrent authorization failed unexpectedly", failure2.get());
        }

        List<PlaybackToken> tokens = playbackTokenRepository.findAll().stream()
            .filter(t -> t.getVideoId().equals(video.getId()) && t.getViewerId().equals(viewerId))
            .toList();
        assertThat(tokens)
            .as("both concurrent authorizations must still succeed and each issue its own token")
            .hasSize(2);

        Long bandwidthUsed = jdbcTemplate.queryForObject(
            "SELECT bandwidth_used_bytes FROM main.video_quotas WHERE user_id = ?", Long.class, ownerId);
        assertThat(bandwidthUsed)
            .as("the two concurrent authorizations of the same (viewerId, videoId) pair must charge "
                + "the owner's bandwidth exactly once between them, not twice")
            .isEqualTo(storageBytes);
    }

    /**
     * An unrelated concurrent authorization of a DIFFERENT video must not be blocked by this
     * video's lock: the lock is per-video (a per-Video-row pessimistic lock), coarser than
     * per-viewer but not global. Two distinct videos, two distinct owners — both must succeed and
     * each must charge its own owner independently, with no cross-contamination between them.
     */
    @Test
    void authorizePlayback_concurrentDifferentVideos_areNotBlockedByEachOthersLock() throws Exception {
        long storageBytes1 = 111_111L;
        long storageBytes2 = 222_222L;
        String ownerId1 = "owner-concurrency-2a";
        String ownerId2 = "owner-concurrency-2b";
        Video video1 = seedVideo(ownerId1, storageBytes1);
        Video video2 = seedVideo(ownerId2, storageBytes2);
        ensureQuotaRow(ownerId1);
        ensureQuotaRow(ownerId2);

        CountDownLatch startTogether = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> failure1 = new AtomicReference<>();
        AtomicReference<Throwable> failure2 = new AtomicReference<>();

        Future<?> call1 = executor.submit(() ->
            raceAndAuthorize(startTogether, video1.getId(), "viewer-concurrency-2a", failure1));
        Future<?> call2 = executor.submit(() ->
            raceAndAuthorize(startTogether, video2.getId(), "viewer-concurrency-2b", failure2));

        call1.get(30, TimeUnit.SECONDS);
        call2.get(30, TimeUnit.SECONDS);
        executor.shutdown();

        if (failure1.get() != null) {
            throw new AssertionError("First video's authorization failed unexpectedly", failure1.get());
        }
        if (failure2.get() != null) {
            throw new AssertionError("Second video's authorization failed unexpectedly", failure2.get());
        }

        Long bandwidthUsed1 = jdbcTemplate.queryForObject(
            "SELECT bandwidth_used_bytes FROM main.video_quotas WHERE user_id = ?", Long.class, ownerId1);
        Long bandwidthUsed2 = jdbcTemplate.queryForObject(
            "SELECT bandwidth_used_bytes FROM main.video_quotas WHERE user_id = ?", Long.class, ownerId2);
        assertThat(bandwidthUsed1)
            .as("a different video's lock must not prevent this video's own charge from landing")
            .isEqualTo(storageBytes1);
        assertThat(bandwidthUsed2)
            .as("a different video's lock must not prevent this video's own charge from landing")
            .isEqualTo(storageBytes2);
    }

    private void raceAndAuthorize(CountDownLatch startTogether, java.util.UUID videoId, String viewerId,
                                   AtomicReference<Throwable> failureSink) {
        try {
            startTogether.countDown();
            startTogether.await(10, TimeUnit.SECONDS);
            playbackService.authorizePlayback(videoId, viewerId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            failureSink.set(t);
        }
    }

    private void ensureQuotaRow(String ownerId) {
        transactionTemplate.execute(status -> jdbcTemplate.update(
            "INSERT INTO main.video_quotas (user_id, storage_used_bytes, bandwidth_used_bytes, bandwidth_period_start) "
                + "VALUES (?, 0, 0, NOW()) ON CONFLICT (user_id) DO NOTHING", ownerId));
    }

    private Video seedVideo(String ownerId, long storageBytes) {
        Video video = new Video();
        video.setOwnerId(ownerId);
        video.setProvider("bunny");
        video.setProviderAssetId("bunny-asset-" + ownerId);
        video.setTitle("test-video.mp4");
        video.setOperationalState(OperationalState.READY);
        video.setAccessState(AccessState.ACTIVE);
        video.setVisibility(Visibility.PRIVATE);
        video.setStorageBytes(storageBytes);
        return videoRepository.save(video);
    }
}
