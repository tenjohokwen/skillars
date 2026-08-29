package com.softropic.skillars.platform.session.api;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.infrastructure.video.SignedPlaybackUrl;
import com.softropic.skillars.infrastructure.video.UploadCredentials;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.security.SecurityIT;
import com.softropic.skillars.platform.session.contract.DrillResponse;
import com.softropic.skillars.platform.session.contract.DrillUploadInitiateRequest;
import com.softropic.skillars.platform.session.service.DrillLibraryService;
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

import java.sql.Timestamp;
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
    private static final long COACH2_USER_ID = 9565000011L;
    private static final String COACH2_EMAIL = "concurrency.upload2@skillars-test.com";

    private UUID coachId;
    private UUID coach2Id;

    @Autowired
    DrillUploadService drillUploadService;

    @Autowired
    DrillLibraryService drillLibraryService;

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

            insertUser(COACH2_USER_ID, COACH2_EMAIL, passwordHash, "COACH");
            grantRole(COACH2_USER_ID, "ROLE_COACH");
            coach2Id = insertCoachProfile(COACH2_USER_ID);
            insertSubscription(coach2Id, "INSTRUCTOR");
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

    private void insertVideoRow(UUID videoId) {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.videos (id, owner_id, provider, provider_asset_id, operational_state, " +
                "access_state, title, visibility, created_at, updated_at) " +
                "VALUES (?, ?, 'bunny', ?, 'READY', 'ACTIVE', 'Test Video', 'PRIVATE', ?, ?)",
                videoId, coachId.toString(), "asset-" + videoId,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
            return null;
        });
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

    /**
     * Deferred-81 AC3: the cross-drill half of the Def14 race this class's own class-level javadoc
     * explicitly said was still open. Two DIFFERENT drillIds sharing one videoId — reachable via
     * the public API only as two PRIVATE clones of the same PLATFORM source (deleteVideo/
     * initiateUpload both require a PRIVATE, caller-owned drill, so the PLATFORM source itself is
     * never a valid target) — must now serialize on the shared videoId via
     * {@code VideoRepository.findByIdForUpdate}, closing the gap where two distinct Drill-row
     * locks previously let both calls observe {@code existsByVideoId()==true} before either
     * committed its own clear.
     *
     * <p>The two clones are owned by two DIFFERENT coaches, not the same coach twice: a
     * {@code idx_drills_clone_uniqueness} DB constraint on {@code (source_drill_id,
     * owner_coach_id)} means one coach can only ever clone a given source drill once — two
     * distinct owners is the only way two clones of one source can coexist.
     */
    @Test
    @Timeout(30)
    void deleteVideo_concurrentCallsOnTwoClonesSharingOneVideoId_doesNotDoublePublishDeletionEvent() throws Exception {
        UUID sourceDrillId = UUID.randomUUID();
        insertDrill(sourceDrillId, "Platform Source Drill", "PLATFORM", null, "ACTIVE");
        UUID videoId = UUID.randomUUID();
        insertVideoRow(videoId);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO session.drill_video_refs (drill_id, video_id, ref_count) VALUES (?, ?, 1)",
                sourceDrillId, videoId);
            return null;
        });

        // Two independent clones of the same PLATFORM source by two different coaches —
        // cloneDrill reads the SOURCE's own ref each time, so both clones end up pointing at the
        // identical videoId, exactly like DrillLibraryService.cloneDrill's own behavior this AC's
        // text describes.
        DrillResponse clone1 = drillLibraryService.cloneDrill(sourceDrillId, COACH_USER_ID);
        DrillResponse clone2 = drillLibraryService.cloneDrill(sourceDrillId, COACH2_USER_ID);
        UUID clone1DrillId = clone1.id();
        UUID clone2DrillId = clone2.id();

        // The PLATFORM source's own ref is never deletable via this service (deleteVideo requires
        // a PRIVATE, caller-owned drill), so it is removed here to isolate the two-CLONE race this
        // test targets — otherwise the source's surviving ref would keep existsByVideoId() true
        // forever regardless of what either clone's deleteVideo call does, and the event would
        // never publish at all.
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM session.drill_video_refs WHERE drill_id = ?", sourceDrillId);
            return null;
        });

        int threadCount = 2;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            Future<?> f1 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                drillUploadService.deleteVideo(clone1DrillId, COACH_USER_ID);
                return null;
            });
            Future<?> f2 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                drillUploadService.deleteVideo(clone2DrillId, COACH2_USER_ID);
                return null;
            });
            f1.get(20, TimeUnit.SECONDS);
            f2.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
            "SELECT video_id FROM session.drill_video_refs WHERE drill_id = ?", UUID.class, clone1DrillId))
            .as("both clones' refs must land cleared")
            .isNull();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT video_id FROM session.drill_video_refs WHERE drill_id = ?", UUID.class, clone2DrillId))
            .as("both clones' refs must land cleared")
            .isNull();

        long publishedCount = applicationEvents.stream(
                com.softropic.skillars.platform.session.contract.VideoPhysicalDeletionEvent.class)
            .filter(e -> e.videoId().equals(videoId))
            .count();
        assertThat(publishedCount)
            .as("the shared-videoId lock must serialize the two cross-drill calls so only the "
                + "second, post-lock re-check publishes exactly once — not zero (orphan) and not "
                + "two (double-publish)")
            .isEqualTo(1);
    }

    /**
     * Deferred-83 AC2: {@link #deleteVideo_concurrentCallsOnTwoClonesSharingOneVideoId_doesNotDoublePublishDeletionEvent()}
     * above proves only the end result — the same outcome could occur by incidental thread
     * scheduling even if the lock were silently removed. This test proves the video-row lock itself
     * (the cross-drill {@code VideoRepository.findByIdForUpdate(videoId)} mechanism this AC targets)
     * actually CAUSES serialization: an external holder — mirroring this exact file's own already-
     * established {@link #initiateUpload_briefContention_succeedsAfterBoundedRetry()} pattern — takes
     * a raw {@code SELECT ... FOR UPDATE} on the video row for a known duration, then
     * {@code deleteVideo} is called on an independent clone sharing that same videoId. Completion
     * only AFTER the hold elapses is direct proof {@link PessimisticLockRetryer} genuinely waited out
     * (retried against) the video-row lock: an unrelated coincidence in scheduling cannot produce
     * this outcome, since the holder is an entirely separate, externally controlled transaction, not
     * a second racing application call whose ordering could get lucky.
     *
     * <p>A Mockito-based interception of {@code VideoRepository.findByIdForUpdate} (recording each
     * caught {@code PessimisticLockingFailureException} before {@link PessimisticLockRetryer} retries
     * it, so the proof would be timing-independent) was tried first. It does not work in this
     * codebase: Spring Data JPA repository methods are interface methods with no bytecode body, so
     * Mockito's {@code callRealMethod()} — needed to preserve genuine locking behavior while still
     * observing the exception — throws {@code MockitoException: Cannot call abstract real method on
     * java object!} for every invocation path tried, including retrieving the spy's own recorded
     * spied instance via {@code Mockito.mockingDetails(...).getMockCreationSettings()
     * .getSpiedInstance()} (which {@code @MockitoSpyBean} leaves {@code null} for this repository).
     * The externally-held-lock design below reuses this file's own accepted elapsed-time-threshold
     * convention instead (same {@code holdMillis - 200} tolerance as the brief-contention test above).
     */
    @Test
    @Timeout(30)
    void deleteVideo_videoRowHeldByAnotherTransaction_waitsOutTheLockBeforeCompleting() throws Exception {
        UUID sourceDrillId = UUID.randomUUID();
        insertDrill(sourceDrillId, "Platform Source Drill Causality", "PLATFORM", null, "ACTIVE");
        UUID videoId = UUID.randomUUID();
        insertVideoRow(videoId);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO session.drill_video_refs (drill_id, video_id, ref_count) VALUES (?, ?, 1)",
                sourceDrillId, videoId);
            return null;
        });

        DrillResponse clone = drillLibraryService.cloneDrill(sourceDrillId, COACH_USER_ID);
        UUID cloneDrillId = clone.id();

        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM session.drill_video_refs WHERE drill_id = ?", sourceDrillId);
            return null;
        });

        long holdMillis = 1200;
        CountDownLatch lockHeld = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> transactionTemplate.execute(status -> {
                jdbcTemplate.query("SELECT id FROM main.videos WHERE id = ? FOR UPDATE",
                    rs -> { }, videoId);
                lockHeld.countDown();
                try {
                    Thread.sleep(holdMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));

            assertThat(lockHeld.await(5, TimeUnit.SECONDS))
                .as("holder must acquire the video-row lock first").isTrue();

            Instant start = Instant.now();
            Future<?> contender = pool.submit(() -> {
                drillUploadService.deleteVideo(cloneDrillId, COACH_USER_ID);
                return null;
            });
            contender.get(20, TimeUnit.SECONDS);
            long elapsedMillis = Duration.between(start, Instant.now()).toMillis();
            holder.get(15, TimeUnit.SECONDS);

            assertThat(elapsedMillis)
                .as("deleteVideo must have genuinely waited out the externally-held video-row lock "
                    + "via PessimisticLockRetryer's retry loop, not completed instantly — an instant "
                    + "completion would mean the videoId findByIdForUpdate lock did not actually "
                    + "serialize against the holder")
                .isGreaterThanOrEqualTo(holdMillis - 200);

            assertThat(jdbcTemplate.queryForObject(
                "SELECT video_id FROM session.drill_video_refs WHERE drill_id = ?", UUID.class, cloneDrillId))
                .as("the delete must still complete correctly once the lock is released")
                .isNull();
        } finally {
            pool.shutdownNow();
        }
    }
}
