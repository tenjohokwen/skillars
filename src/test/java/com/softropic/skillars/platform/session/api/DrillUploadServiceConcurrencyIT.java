package com.softropic.skillars.platform.session.api;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.infrastructure.video.SignedPlaybackUrl;
import com.softropic.skillars.infrastructure.video.UploadCredentials;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.security.SecurityIT;
import com.softropic.skillars.platform.session.contract.DrillUploadInitiateRequest;
import com.softropic.skillars.platform.session.service.DrillUploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.context.jdbc.Sql;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Story Deferred-75 AC5: {@code DrillRepository.findByIdForUpdate}'s {@code @Lock(PESSIMISTIC_WRITE)}
 * (mirroring {@code CoachProfileRepository.findByIdForUpdate} exactly, via {@link PessimisticLockRetryer})
 * must serialize concurrent {@code DrillUploadService.initiateUpload}/{@code deleteVideo} calls on the
 * same drill row. Structure mirrors {@code SessionPackPurchaseLockContentionIT} — an external
 * {@code SELECT ... FOR UPDATE} on the drill row simulates a competing holder, proving the bounded-retry
 * behavior for both a brief (absorbed) and a prolonged (surfaced as 409) contention window.
 *
 * <p><strong>Scope note on the ledger's Def14 item:</strong> the lock here is per-{@code Drill} row
 * (keyed by {@code drillId}), not per-{@code videoId}. This fully closes the same-drill double-call race
 * {@link #deleteVideo_concurrentCallsOnSameDrill_doesNotDoublePublishDeletionEvent()} below proves — but
 * Def14's own original wording ("concurrent deletes on <em>different</em> drills sharing the same
 * videoId") describes a different, still-open race: {@code DrillLibraryService.cloneDrill} (:134-136)
 * confirms two distinct drill rows can share one {@code video_id} via {@code drill_video_refs}, and two
 * calls on two <em>different</em> drillIds acquire two <em>different</em> row locks, so they do not
 * serialize against each other. This class does not claim to test that cross-drill scenario — see the
 * corrected AC12 ledger note.
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
@RecordApplicationEvents
class DrillUploadServiceConcurrencyIT extends BaseSessionIT {

    private static final long COACH_USER_ID = 9565000010L;
    private static final String COACH_EMAIL = "concurrency.upload@skillars-test.com";

    private UUID coachId;

    @Autowired
    DrillUploadService drillUploadService;

    @Autowired
    ApplicationEvents applicationEvents;

    @MockitoBean
    VideoProviderAdapter videoProviderAdapter;

    @BeforeEach
    void setUp() {
        when(videoProviderAdapter.initializeUpload(anyString(), anyLong())).thenAnswer(inv ->
            new UploadCredentials("bunny-upload-id-" + UUID.randomUUID(), "https://tus.bunny.net/upload/test",
                "0".repeat(64), Instant.now().plusSeconds(3600).getEpochSecond(), 0L));
        when(videoProviderAdapter.generatePlaybackUrl(anyString(), any()))
            .thenReturn(new SignedPlaybackUrl("https://cdn.example.com/play", Instant.now().plusSeconds(7200)));

        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9565, 'ROLE_COACH', 'ACTIVE', 'system', NOW()) ON CONFLICT (name) DO NOTHING"
            );
            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            grantRole(COACH_USER_ID, "ROLE_COACH");
            coachId = insertCoachProfile(COACH_USER_ID);
            insertSubscription(coachId, "INSTRUCTOR");
            return null;
        });
    }

    private UUID insertUploadTestDrill() {
        UUID drillId = UUID.randomUUID();
        insertDrill(drillId, "Concurrency Test Drill", "PRIVATE", coachId, "ACTIVE");
        return drillId;
    }

    private DrillUploadInitiateRequest uploadRequest() {
        return new DrillUploadInitiateRequest("demo.mp4", 1024L, "video/mp4", 10);
    }

    /**
     * A competing lock held well within {@link PessimisticLockRetryer}'s retry budget (~3.2s across its
     * default 8 attempts) must not surface a 409 — the retry loop should absorb it and initiateUpload
     * should still succeed, exactly as it would with no contention at all.
     */
    @Test
    @Timeout(30)
    void initiateUpload_briefContention_succeedsAfterBoundedRetry() throws Exception {
        UUID drillId = insertUploadTestDrill();
        long holdMillis = 1200;
        CountDownLatch lockHeld = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> transactionTemplate.execute(status -> {
                jdbcTemplate.query("SELECT id FROM session.drills WHERE id = ? FOR UPDATE",
                    rs -> { }, drillId);
                lockHeld.countDown();
                try {
                    Thread.sleep(holdMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));

            assertThat(lockHeld.await(5, TimeUnit.SECONDS)).as("holder must acquire the lock first").isTrue();

            Instant start = Instant.now();
            Future<?> contender = pool.submit(() ->
                drillUploadService.initiateUpload(drillId, COACH_USER_ID, uploadRequest()));
            contender.get(20, TimeUnit.SECONDS);
            long elapsedMillis = Duration.between(start, Instant.now()).toMillis();
            holder.get(15, TimeUnit.SECONDS);

            assertThat(elapsedMillis)
                .as("must have actually waited out the brief contention via retry, not skipped it")
                .isGreaterThanOrEqualTo(holdMillis - 200);

            assertThat(jdbcTemplate.queryForObject(
                "SELECT video_id FROM session.drill_video_refs WHERE drill_id = ?", UUID.class, drillId))
                .as("brief contention must not prevent the upload from eventually landing")
                .isNotNull();
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * A competing lock held past {@link PessimisticLockRetryer}'s retry budget must still surface the
     * contention as {@code PessimisticLockingFailureException}, bounded well before the full hold time.
     */
    @Test
    @Timeout(30)
    void initiateUpload_prolongedContention_failsWithBoundedPessimisticLockingFailure() throws Exception {
        UUID drillId = insertUploadTestDrill();
        long holdMillis = 8000;
        CountDownLatch lockHeld = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> transactionTemplate.execute(status -> {
                jdbcTemplate.query("SELECT id FROM session.drills WHERE id = ? FOR UPDATE",
                    rs -> { }, drillId);
                lockHeld.countDown();
                try {
                    Thread.sleep(holdMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));

            assertThat(lockHeld.await(5, TimeUnit.SECONDS)).as("holder must acquire the lock first").isTrue();

            Instant start = Instant.now();
            Future<?> contender = pool.submit(() ->
                drillUploadService.initiateUpload(drillId, COACH_USER_ID, uploadRequest()));

            try {
                contender.get(20, TimeUnit.SECONDS);
                throw new AssertionError("expected initiateUpload to fail with PessimisticLockingFailureException");
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(PessimisticLockingFailureException.class);
            }
            long elapsedMillis = Duration.between(start, Instant.now()).toMillis();
            holder.get(15, TimeUnit.SECONDS);

            assertThat(elapsedMillis)
                .as("retry budget exhaustion must be bounded, well under the %dms hold time", holdMillis)
                .isLessThan(4500);

            assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session.drill_video_refs WHERE drill_id = ?", Integer.class, drillId))
                .as("the failed attempt must not have partially created a video ref")
                .isEqualTo(0);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Two concurrent {@code deleteVideo} calls on the SAME drillId — before AC5, both could observe
     * {@code existsByVideoId()==false} before either committed its own clear, double-publishing
     * {@code VideoPhysicalDeletionEvent} (the ledger's Def14 item, for this same-drill variant of it).
     */
    @Test
    @Timeout(30)
    void deleteVideo_concurrentCallsOnSameDrill_doesNotDoublePublishDeletionEvent() throws Exception {
        UUID drillId = insertUploadTestDrill();
        UUID videoId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO session.drill_video_refs (drill_id, video_id, ref_count) VALUES (?, ?, 1)",
                drillId, videoId);
            return null;
        });

        int threadCount = 2;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            Future<?>[] futures = new Future<?>[threadCount];
            for (int i = 0; i < threadCount; i++) {
                futures[i] = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    drillUploadService.deleteVideo(drillId, COACH_USER_ID);
                    return null;
                });
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS))
                .as("both deleteVideo calls must complete within 20 seconds")
                .isTrue();
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
            "SELECT video_id FROM session.drill_video_refs WHERE drill_id = ?", UUID.class, drillId))
            .as("both concurrent deletes must land on a cleared ref")
            .isNull();

        long publishedCount = applicationEvents.stream(
                com.softropic.skillars.platform.session.contract.VideoPhysicalDeletionEvent.class)
            .filter(e -> e.videoId().equals(videoId))
            .count();
        assertThat(publishedCount)
            .as("findByIdForUpdate's lock must serialize the two calls so only the second, "
                + "post-lock re-check publishes exactly once — not zero (orphan) and not two (double-publish)")
            .isEqualTo(1);
    }
}
