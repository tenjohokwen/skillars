package com.softropic.skillars.platform.reviews.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.admin.service.AdminReviewService;
import com.softropic.skillars.platform.reviews.contract.ReviewErrorCode;
import com.softropic.skillars.platform.reviews.contract.ReviewFlagReason;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.utils.ConcurrencyLockWaitSupport;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-130 AC1 Fix 1. {@code ReviewFlagIT} drives {@code POST /api/reviews/.../flag}
 * through {@code HttpTestClient} and has no seam to pause inside {@code flag()} between its read
 * and its threshold-crossing write, so the reachable trigger identified during story review —
 * {@code ReviewSubmissionService.updateReview} committing between {@code flag()}'s row read and its
 * conditional {@code moderationStatus} write — needs a service-level test instead. Mirrors
 * {@code RadarCompositeCalculationServiceConcurrencyIT}'s own shape: autowire the services directly
 * against real Testcontainers Postgres, and use a {@code TransactionTemplate} + latch on a separate
 * thread to hold the lock-holding call's own row lock open past its normal method boundary (by
 * calling it INSIDE an outer {@code TransactionTemplate.execute} and pausing before that lambda
 * returns) so a concurrent {@code flag()} call is forced to contend for the SAME row lock rather than
 * proceeding on a stale unlocked read.
 *
 * <p>skillars-deferred-132 AC1 Fix 2: {@code flag()}'s own lock moved from the shared blocking
 * {@code CoachReviewRepository.findByIdForUpdate} to a NOWAIT-only {@code findByIdForUpdateNoWait}
 * wrapped in {@code PessimisticLockRetryer}. A NOWAIT contender never sits in a live Postgres wait
 * state a {@code pg_locks} poll could observe, so the two tests below that used to poll for a genuine
 * blocking waiter now use {@link ConcurrencyLockWaitSupport#awaitFirstLockAttempt()} as a bounded
 * pre-release delay, paired with {@link ConcurrencyLockWaitSupport#assertGenuineLockRetryOccurred}
 * (tagged {@value #FLAG_LOCK_NAME}, skillars-deferred-132 AC1 Fix 5) as a deterministic post-hoc proof
 * that {@code flag()} genuinely contended for the lock and retried, not merely raced past it
 * uncontended.
 */
class ReviewFlagServiceConcurrencyIT extends AbstractIntegrationTest {

    @Autowired private ReviewFlagService reviewFlagService;
    @Autowired private ReviewSubmissionService reviewSubmissionService;
    @Autowired private AdminReviewService adminReviewService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private MeterRegistry meterRegistry;

    private static final String FLAG_LOCK_NAME = "ReviewFlagService.flag";

    private static final long AUTHOR_ID = 8060_000_001L;
    private static final long FLAGGER1_ID = 8060_000_002L;
    private static final long FLAGGER2_ID = 8060_000_003L;
    private static final long FLAGGER3_ID = 8060_000_004L;
    private static final long COACH_USER_ID = 8060_000_010L;

    private static final String ORIGINAL_BODY = "Great coach!";
    private static final String NEW_BODY = "Actually, even better than I first thought!";
    private static final Integer NEW_RATING = 5;

    private UUID coachProfileId;
    private UUID reviewId;

    @BeforeEach
    void setUp() {
        coachProfileId = UUID.randomUUID();
        reviewId = UUID.randomUUID();

        // geminiClient is a MockitoBean hoisted onto AbstractIntegrationTest. updateReview's
        // AFTER_COMMIT ReviewModerationService listener calls it; an unstubbed mock returns null,
        // which NPEs inside that listener's own switch(verdict) BEFORE it ever takes the review row's
        // lock or writes anything — a harmless, pre-existing pattern (see ReviewUpdateIT's own
        // comment on this exact mock). Deliberately left unstubbed rather than stubbed SAFE/throwing:
        // either of those would make the listener itself acquire this row's lock and race flag()'s
        // own lock attempt for it, on top of the race this test exists to exercise.

        transactionTemplate.execute(status -> {
            insertUser(AUTHOR_ID, "author.race@skillars-test.com", "PARENT");
            insertUser(FLAGGER1_ID, "flagger1.race@skillars-test.com", "PARENT");
            insertUser(FLAGGER2_ID, "flagger2.race@skillars-test.com", "PARENT");
            insertUser(FLAGGER3_ID, "flagger3.race@skillars-test.com", "PARENT");
            insertUser(COACH_USER_ID, "coach.race@skillars-test.com", "COACH");

            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Race Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID);

            long playerId = AUTHOR_ID + 1_000_000L;
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Race Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                playerId, Date.valueOf(LocalDate.now().minusYears(18)),
                AUTHOR_ID, Timestamp.from(Instant.now()));

            // Eligibility for updateReview's own checkEligibility: a COMPLETED booking within the
            // default 14-day submissionWindowDays, authored by AUTHOR_ID.
            jdbcTemplate.update(
                "INSERT INTO booking.bookings " +
                "(id, coach_id, parent_id, player_id, status, requested_start_time, requested_end_time, " +
                " version, created_at, updated_at, canonical_timezone) " +
                "VALUES (?, ?, ?, ?, 'COMPLETED', ?, ?, 0, ?, ?, 'Europe/Berlin')",
                UUID.randomUUID(), coachProfileId, AUTHOR_ID, playerId,
                Timestamp.from(Instant.now().minusSeconds(7200)),
                Timestamp.from(Instant.now().minusSeconds(3600)),
                Timestamp.from(Instant.now().minusSeconds(86400 * 3)),
                Timestamp.from(Instant.now().minusSeconds(3600)));

            // APPROVED, well outside updateReview's 365-day UPDATE_TOO_SOON guard.
            jdbcTemplate.update(
                "INSERT INTO reviews.coach_reviews " +
                "(review_id, coach_id, author_id, author_role, rating, body, moderation_status, " +
                " moderation_epoch, created_at, last_modified_at) " +
                "VALUES (?, ?, ?, 'PARENT', 4, ?, 'APPROVED', 0, ?, ?)",
                reviewId, coachProfileId, AUTHOR_ID, ORIGINAL_BODY,
                Timestamp.from(Instant.now().minusSeconds(86400L * 400)),
                Timestamp.from(Instant.now().minusSeconds(86400L * 400)));

            // Two pre-existing open flags — the third (via reviewFlagService.flag below) reaches the
            // default threshold of 3.
            jdbcTemplate.update(
                "INSERT INTO reviews.review_flags (review_id, flagged_by, reason, created_at) " +
                "VALUES (?, ?, 'FAKE_REVIEW', ?)",
                reviewId, FLAGGER1_ID, Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO reviews.review_flags (review_id, flagged_by, reason, created_at) " +
                "VALUES (?, ?, 'OFFENSIVE_CONTENT', ?)",
                reviewId, FLAGGER2_ID, Timestamp.from(Instant.now()));

            return null;
        });
    }

    /**
     * Proves the fix: with {@code flag()} now locking the review row as its own first read,
     * {@code updateReview}'s commit and {@code flag()}'s threshold-crossing write can no longer
     * interleave. Before the fix, {@code flag()} would take an unlocked stale snapshot, let
     * {@code updateReview} commit PENDING/new rating+body/epoch+1 in between, then flush a full-row
     * UPDATE from the stale instance — silently reverting the author's edit while incorrectly
     * auto-holding a review that had already moved off APPROVED. After the fix, {@code flag()}
     * blocks until {@code updateReview}'s transaction actually commits, observes the FRESH
     * (non-APPROVED) state, correctly skips the auto-hold write, and the flag insert itself still
     * succeeds unaffected.
     */
    @Test
    void concurrentUpdateReview_doesNotRevertEditOrWronglyAutoHold() throws Exception {
        // skillars-deferred-132 AC1 Fix 5: captured before the flagger's own findByIdForUpdateNoWait
        // can retry, so the poll below asserts genuine growth past this call's own baseline, tagged to
        // THIS lock site, rather than the JVM-wide (pre-Fix-5) or cross-test (post-Fix-5, untagged)
        // total left over from other tests in the run.
        double lockRetryBaseline = ConcurrencyLockWaitSupport.currentLockRetryCount(meterRegistry, FLAG_LOCK_NAME);
        CountDownLatch updateLockHeld = new CountDownLatch(1);
        CountDownLatch releaseUpdate = new CountDownLatch(1);
        AtomicReference<Throwable> updaterFailure = new AtomicReference<>();
        AtomicReference<Instant> updateCommittedAt = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> updater = executor.submit(() -> {
                try {
                    transactionTemplate.execute(status -> {
                        reviewSubmissionService.updateReview(reviewId, AUTHOR_ID, NEW_RATING, NEW_BODY);
                        updateLockHeld.countDown();
                        try {
                            // Holds the row lock open past updateReview's own method boundary, forcing
                            // flag()'s own findByIdForUpdate to block on it below.
                            boolean released = releaseUpdate.await(30, TimeUnit.SECONDS);
                            if (!released) {
                                throw new AssertionError("releaseUpdate was never signalled within 30s");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return null;
                    });
                    updateCommittedAt.set(Instant.now());
                } catch (Throwable t) {
                    updaterFailure.set(t);
                }
            });

            AtomicReference<Throwable> flaggerFailure = new AtomicReference<>();
            AtomicReference<Instant> flagCompletedAt = new AtomicReference<>();
            Future<?> flagger = executor.submit(() -> {
                try {
                    boolean lockHeld = updateLockHeld.await(10, TimeUnit.SECONDS);
                    if (!lockHeld) {
                        throw new AssertionError("updateLockHeld was never signalled within 10s");
                    }
                    reviewFlagService.flag(reviewId, FLAGGER3_ID, ReviewFlagReason.CONFLICT_OF_INTEREST,
                        "concurrency test");
                    flagCompletedAt.set(Instant.now());
                } catch (Throwable t) {
                    flaggerFailure.set(t);
                }
            });

            assertThat(updateLockHeld.await(10, TimeUnit.SECONDS)).isTrue();
            // skillars-deferred-132 AC1 Fix 2: flag()'s own findByIdForUpdateNoWait is NOWAIT now —
            // it never sits in a live Postgres wait state a pg_locks poll could observe. This bounded
            // delay gives the flagger's own NOWAIT failure and first backoff a real chance to happen
            // before the lock releases; assertGenuineLockRetryOccurred below then proves it actually
            // did (see ConcurrencyLockWaitSupport's own javadoc for why a live poll can't replace it).
            ConcurrencyLockWaitSupport.awaitFirstLockAttempt();
            releaseUpdate.countDown();

            updater.get(30, TimeUnit.SECONDS);
            flagger.get(30, TimeUnit.SECONDS);

            if (updaterFailure.get() != null) {
                throw new AssertionError("updateReview thread failed", updaterFailure.get());
            }
            if (flaggerFailure.get() != null) {
                throw new AssertionError("flag() thread failed", flaggerFailure.get());
            }

            ConcurrencyLockWaitSupport.assertGenuineLockRetryOccurred(meterRegistry, FLAG_LOCK_NAME, lockRetryBaseline);

            assertThat(flagCompletedAt.get())
                .as("flag()'s own row UPDATE (or, before the fix, its plain findById-then-save) must "
                    + "not complete until updateReview's transaction actually commits and releases the "
                    + "row lock — this proves general serialization on the row, not by itself that "
                    + "flag()'s READ is now locked (an unlocked read followed by the eventual UPDATE "
                    + "would block on the same held lock too); the rating/body/epoch assertions below "
                    + "are what actually distinguish the fix from the pre-fix behavior")
                .isAfterOrEqualTo(updateCommittedAt.get());

            Integer rating = jdbcTemplate.queryForObject(
                "SELECT rating FROM reviews.coach_reviews WHERE review_id = ?", Integer.class, reviewId);
            String body = jdbcTemplate.queryForObject(
                "SELECT body FROM reviews.coach_reviews WHERE review_id = ?", String.class, reviewId);
            Long epoch = jdbcTemplate.queryForObject(
                "SELECT moderation_epoch FROM reviews.coach_reviews WHERE review_id = ?", Long.class, reviewId);
            String moderationStatus = jdbcTemplate.queryForObject(
                "SELECT moderation_status FROM reviews.coach_reviews WHERE review_id = ?", String.class, reviewId);
            Integer flagCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reviews.review_flags WHERE review_id = ?", Integer.class, reviewId);

            assertThat(rating).as("updateReview's new rating must survive, not be reverted by flag()'s write")
                .isEqualTo(NEW_RATING);
            assertThat(body).as("updateReview's new body must survive, not be reverted by flag()'s write")
                .isEqualTo(NEW_BODY);
            assertThat(epoch).as("updateReview's epoch bump must survive").isEqualTo(1L);
            assertThat(moderationStatus)
                .as("flag()'s auto-hold guard requires APPROVED; the review is PENDING (set by the "
                    + "already-committed updateReview) by the time flag() observes it under the lock, so "
                    + "no auto-hold write must happen")
                .isEqualTo("PENDING");
            assertThat(flagCount).as("the third flag insert itself must still succeed").isEqualTo(3);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Complements the test above (which only proves the auto-hold write is correctly SKIPPED once the
     * review has moved off {@code APPROVED}) with the case that write must still fire: a genuine lock
     * holder that does NOT change {@code moderationStatus} — a raw {@code SELECT ... FOR UPDATE},
     * mirroring {@code RadarCompositeCalculationServiceConcurrencyIT}'s own locker-thread shape — so
     * {@code flag()} blocks on real contention, then observes the review still {@code APPROVED} and
     * correctly applies the threshold-crossing write (code review 2026-09-23, Patch: the existing
     * non-concurrent {@code ReviewFlagIT.flagThresholdReached_reviewSetToUnderReview} exercises this
     * write too, but with no genuine contention — trivially true even against the pre-fix unlocked
     * {@code findById}, so it does not by itself prove anything about the lock this story adds).
     */
    @Test
    void flagUnderGenuineLockContention_stillAppliesAutoHoldOnceThresholdReached() throws Exception {
        double lockRetryBaseline = ConcurrencyLockWaitSupport.currentLockRetryCount(meterRegistry, FLAG_LOCK_NAME);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        AtomicReference<Throwable> lockerFailure = new AtomicReference<>();
        AtomicReference<Instant> lockReleasedAt = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> locker = executor.submit(() -> {
                try {
                    transactionTemplate.execute(status -> {
                        jdbcTemplate.queryForObject(
                            "SELECT review_id FROM reviews.coach_reviews WHERE review_id = ? FOR UPDATE",
                            UUID.class, reviewId);
                        lockHeld.countDown();
                        try {
                            boolean released = releaseLock.await(30, TimeUnit.SECONDS);
                            if (!released) {
                                throw new AssertionError("releaseLock was never signalled within 30s");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return null;
                    });
                    lockReleasedAt.set(Instant.now());
                } catch (Throwable t) {
                    lockerFailure.set(t);
                }
            });

            AtomicReference<Throwable> flaggerFailure = new AtomicReference<>();
            AtomicReference<Instant> flagCompletedAt = new AtomicReference<>();
            Future<?> flagger = executor.submit(() -> {
                try {
                    boolean held = lockHeld.await(10, TimeUnit.SECONDS);
                    if (!held) {
                        throw new AssertionError("lockHeld was never signalled within 10s");
                    }
                    reviewFlagService.flag(reviewId, FLAGGER3_ID, ReviewFlagReason.CONFLICT_OF_INTEREST,
                        "concurrency test");
                    flagCompletedAt.set(Instant.now());
                } catch (Throwable t) {
                    flaggerFailure.set(t);
                }
            });

            assertThat(lockHeld.await(10, TimeUnit.SECONDS)).isTrue();
            // skillars-deferred-132 AC1 Fix 2: flag()'s own findByIdForUpdateNoWait is NOWAIT now —
            // the raw locker thread's SELECT ... FOR UPDATE still genuinely holds the row lock, but
            // flag() fails fast against it and retries in Java rather than sitting in a Postgres wait
            // state, so a pg_locks poll can no longer observe it. Same bounded-delay-then-post-hoc-
            // assertion pattern as the test above.
            ConcurrencyLockWaitSupport.awaitFirstLockAttempt();
            releaseLock.countDown();

            locker.get(30, TimeUnit.SECONDS);
            flagger.get(30, TimeUnit.SECONDS);

            if (lockerFailure.get() != null) {
                throw new AssertionError("locker thread failed", lockerFailure.get());
            }
            if (flaggerFailure.get() != null) {
                throw new AssertionError("flag() thread failed", flaggerFailure.get());
            }

            ConcurrencyLockWaitSupport.assertGenuineLockRetryOccurred(meterRegistry, FLAG_LOCK_NAME, lockRetryBaseline);

            assertThat(flagCompletedAt.get())
                .as("flag() must not complete until the raw FOR UPDATE lock is released — proving it "
                    + "genuinely contended for the row, not merely ran uncontended")
                .isAfterOrEqualTo(lockReleasedAt.get());

            String moderationStatus = jdbcTemplate.queryForObject(
                "SELECT moderation_status FROM reviews.coach_reviews WHERE review_id = ?", String.class, reviewId);
            String heldReason = jdbcTemplate.queryForObject(
                "SELECT held_reason FROM reviews.coach_reviews WHERE review_id = ?", String.class, reviewId);
            Integer flagCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reviews.review_flags WHERE review_id = ?", Integer.class, reviewId);

            assertThat(flagCount).isEqualTo(3);
            assertThat(moderationStatus)
                .as("the review stayed APPROVED throughout (nothing else raced it), so once genuine "
                    + "lock contention resolves, flag()'s own auto-hold write must still fire at the "
                    + "threshold")
                .isEqualTo("UNDER_REVIEW");
            assertThat(heldReason).isEqualTo("FLAG_THRESHOLD");
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * The boundary the review flagged as untested: one flag short of the default threshold (3) must
     * NOT trigger the auto-hold write, even though the review is APPROVED — only
     * {@code openFlagCount >= threshold} does. Non-concurrent; the two tests above already cover the
     * threshold-met case both with and without genuine lock contention.
     */
    @Test
    void flagBelowThreshold_doesNotAutoHold() {
        // setUp() already seeded 2 open flags (FLAGGER1, FLAGGER2); this third flag brings the count to
        // 2 open + this insert... wait: FLAGGER3 flagging brings total open flags to 3. To stay BELOW
        // threshold, resolve one of the two pre-seeded flags first so only 1 remains open before this
        // flag call — 1 (pre-existing) + 1 (this call) = 2, one short of the default threshold of 3.
        // Must run inside an explicit transaction — this datasource has autoCommit disabled, so a bare
        // jdbcTemplate.update() with no active Spring transaction never actually commits (the implicit
        // rollback happens silently when the connection is released back to the pool).
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "UPDATE reviews.review_flags SET resolved_at = now() WHERE review_id = ? AND flagged_by = ?",
                reviewId, FLAGGER1_ID);
            return null;
        });

        reviewFlagService.flag(reviewId, FLAGGER3_ID, ReviewFlagReason.CONFLICT_OF_INTEREST, "below threshold");

        String moderationStatus = jdbcTemplate.queryForObject(
            "SELECT moderation_status FROM reviews.coach_reviews WHERE review_id = ?", String.class, reviewId);
        assertThat(moderationStatus)
            .as("openFlagCount (2) is one short of the default threshold (3); auto-hold must not fire")
            .isEqualTo("APPROVED");
    }

    /**
     * skillars-deferred-131 AC2 Fix 5. A pre-fix {@code flag()} locked the review row as its very
     * first statement, so a repeat-flag no-op serialized admin moderation behind a call that was
     * always going to reject. Post-fix, the four write-independent guards (including
     * {@code ALREADY_FLAGGED}) run on an unlocked scalar projection before any lock is taken — proven
     * here by having the repeat-flag call complete WHILE a concurrent holder still has the row's
     * {@code FOR UPDATE} lock: if {@code flag()} still contended for that lock, this would time out
     * waiting for {@code releaseLock} instead of completing.
     */
    @Test
    void repeatFlagNoOp_doesNotBlockOnRowLock_completesWhileConcurrentLockIsStillHeld() throws Exception {
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        AtomicReference<Throwable> lockerFailure = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> locker = executor.submit(() -> {
                try {
                    transactionTemplate.execute(status -> {
                        jdbcTemplate.queryForObject(
                            "SELECT review_id FROM reviews.coach_reviews WHERE review_id = ? FOR UPDATE",
                            UUID.class, reviewId);
                        lockHeld.countDown();
                        try {
                            boolean released = releaseLock.await(30, TimeUnit.SECONDS);
                            if (!released) {
                                throw new AssertionError("releaseLock was never signalled within 30s");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return null;
                    });
                } catch (Throwable t) {
                    lockerFailure.set(t);
                }
            });

            assertThat(lockHeld.await(10, TimeUnit.SECONDS)).isTrue();

            // FLAGGER1 already has an open flag from setUp() — a pure no-op rejection path.
            Future<Throwable> repeatFlag = executor.submit(() -> {
                try {
                    reviewFlagService.flag(reviewId, FLAGGER1_ID, ReviewFlagReason.FAKE_REVIEW, "repeat");
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });

            Throwable outcome = repeatFlag.get(5, TimeUnit.SECONDS);
            assertThat(outcome).isInstanceOf(OperationNotAllowedException.class);
            assertThat(((OperationNotAllowedException) outcome).getErrorCode())
                .isEqualTo(ReviewErrorCode.ALREADY_FLAGGED);

            releaseLock.countDown();
            locker.get(15, TimeUnit.SECONDS);
            if (lockerFailure.get() != null) {
                throw new AssertionError("locker thread failed", lockerFailure.get());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * skillars-deferred-131 AC2 Fix 6. A Mockito unit test alone only proves the parser matches its
     * own hand-built fixture — this pins the REAL Postgres exception shape: {@code flag()} racing a
     * raw, independently-held conflicting {@code review_flags} row (see
     * {@link #holdConflictingFlagRow}) for the same {@code flaggedBy} against the unique index
     * {@code review_flags_unique_flagger} genuinely still maps to {@code ALREADY_FLAGGED}.
     *
     * <p>Code review 2026-09-23: a plain latch-released race (the original shape here) can pass even on
     * a REVERTED Fix 6. {@code flag()}'s unlocked {@code existsByReviewIdAndFlaggedBy} pre-check runs
     * before {@code saveAndFlush} — if the conflicting row were already visible/committed by the time
     * this call's pre-check runs, that pre-check alone would catch the duplicate directly, without ever
     * reaching {@code saveAndFlush}'s catch block this test exists to pin. Holding the conflicting row's
     * transaction open (uncommitted) forces this call down the real flush-time path instead.
     *
     * <p><strong>skillars-deferred-132 AC1/AC2 correction:</strong> the original version of this test
     * held the winner's row open via an outer {@code transactionTemplate.execute(...)} wrapper around a
     * real {@code flag()} call. Two changes this same story makes broke that: Fix 2 converted
     * {@code flag()}'s own lock to NOWAIT+retry (so the loser no longer sits in a live Postgres wait a
     * {@code pg_locks} poll could observe — irrelevant here since this test never polled pg_locks, but
     * relevant to why a "hold winner open, force loser to block" framing no longer describes the actual
     * mechanism), and Fix 7 made {@code flag()} run in its own {@code REQUIRES_NEW} transaction, which
     * commits (and releases the winner's row) as soon as {@code flag()} returns regardless of any
     * enclosing transaction — silently defeating the outer-wrapper hold-open technique entirely (same
     * finding as {@code ReviewSubmissionServiceConcurrencyIT}'s own identical correction).
     * {@link #holdConflictingFlagRow} instead holds the conflicting unique-index entry open from a
     * plain {@code transactionTemplate.execute(...)} block that never calls {@code flag()} at all.
     */
    @Test
    void concurrentDuplicateFlagFromSameFlagger_loserGetsAlreadyFlaggedViaRealConstraintViolation() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch rowHeld = new CountDownLatch(1);
            CountDownLatch releaseRow = new CountDownLatch(1);
            AtomicReference<Throwable> holderFailure = new AtomicReference<>();

            Future<?> holder = executor.submit(() ->
                holdConflictingFlagRow(reviewId, FLAGGER3_ID, rowHeld, releaseRow, holderFailure));

            Callable<Throwable> flagTask = () -> {
                assertThat(rowHeld.await(10, TimeUnit.SECONDS)).isTrue();
                try {
                    reviewFlagService.flag(reviewId, FLAGGER3_ID, ReviewFlagReason.CONFLICT_OF_INTEREST, "race-loser");
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            };
            Future<Throwable> flagger = executor.submit(flagTask);

            // Gives flag()'s own pre-check + findByIdForUpdateNoWait + saveAndFlush a real chance to
            // run before the held row is released.
            Thread.sleep(300);
            releaseRow.countDown();

            holder.get(30, TimeUnit.SECONDS);
            Throwable flagFailure = flagger.get(30, TimeUnit.SECONDS);

            if (holderFailure.get() != null) {
                throw new AssertionError("row-holder thread failed", holderFailure.get());
            }
            assertThat(flagFailure).isInstanceOf(OperationNotAllowedException.class);
            assertThat(((OperationNotAllowedException) flagFailure).getErrorCode())
                .as("the real Postgres unique-index violation must still map to ALREADY_FLAGGED, not propagate uncaught")
                .isEqualTo(ReviewErrorCode.ALREADY_FLAGGED);

            Integer flagCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reviews.review_flags WHERE review_id = ? AND flagged_by = ?",
                Integer.class, reviewId, FLAGGER3_ID);
            assertThat(flagCount).as("exactly one flag row for this flagger, never two").isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * skillars-deferred-132 AC2 Fix 7. Proves the actual point of this fix for {@code flag()}: called
     * from WITHIN a hypothetical outer transaction, it must not leave that outer transaction
     * rollback-only when its own {@code saveAndFlush} hits the real unique-constraint violation. See
     * {@code ReviewSubmissionServiceConcurrencyIT
     * .concurrentSubmit_calledFromWithinAnOuterTransaction_loserStillGetsCleanAlreadySubmitted}'s own
     * Javadoc for the full pre-/post-Fix-7 mechanism this mirrors.
     *
     * <p>The outer transaction touches an unrelated row ({@code main."user"}, not
     * {@code review_flags_unique_flagger}) per Fix 7's own self-deadlock warning.
     */
    @Test
    void concurrentFlag_calledFromWithinAnOuterTransaction_loserStillGetsCleanAlreadyFlagged() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch rowHeld = new CountDownLatch(1);
            CountDownLatch releaseRow = new CountDownLatch(1);
            AtomicReference<Throwable> holderFailure = new AtomicReference<>();

            Future<?> holder = executor.submit(() ->
                holdConflictingFlagRow(reviewId, FLAGGER3_ID, rowHeld, releaseRow, holderFailure));

            assertThat(rowHeld.await(10, TimeUnit.SECONDS)).isTrue();

            AtomicReference<Throwable> flagFailure = new AtomicReference<>();
            AtomicReference<Throwable> outerTransactionFailure = new AtomicReference<>();
            Future<?> outerCaller = executor.submit(() -> {
                try {
                    transactionTemplate.execute(status -> {
                        jdbcTemplate.update(
                            "UPDATE main.\"user\" SET last_modified_date = now() WHERE id = ?", FLAGGER3_ID);
                        try {
                            reviewFlagService.flag(reviewId, FLAGGER3_ID, ReviewFlagReason.CONFLICT_OF_INTEREST,
                                "race-loser-outer-tx");
                        } catch (OperationNotAllowedException e) {
                            flagFailure.set(e);
                        }
                        return null;
                    });
                } catch (Throwable t) {
                    outerTransactionFailure.set(t);
                }
                return null;
            });

            Thread.sleep(300);
            releaseRow.countDown();

            holder.get(30, TimeUnit.SECONDS);
            outerCaller.get(30, TimeUnit.SECONDS);

            if (holderFailure.get() != null) {
                throw new AssertionError("row-holder thread failed", holderFailure.get());
            }
            assertThat(outerTransactionFailure.get())
                .as("the outer transaction's own commit must succeed cleanly — an "
                    + "UnexpectedRollbackException here means flag()'s REQUIRES_NEW isolation did not "
                    + "actually protect the outer caller")
                .isNull();
            assertThat(flagFailure.get())
                .as("flag() must still translate the real unique-constraint violation to a clean "
                    + "business exception even when called from inside an outer transaction")
                .isInstanceOf(OperationNotAllowedException.class);
            assertThat(((OperationNotAllowedException) flagFailure.get()).getErrorCode())
                .isEqualTo(ReviewErrorCode.ALREADY_FLAGGED);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * skillars-deferred-132 AC2 Fix 7. Holds a raw {@code review_flags} row — with the SAME
     * {@code (review_id, flagged_by)} {@code flag()} would insert — open (uncommitted) in its own
     * transaction until {@code release} counts down, then commits. Deliberately does NOT call
     * {@code flag()} itself — see {@link #concurrentDuplicateFlagFromSameFlagger_loserGetsAlreadyFlaggedViaRealConstraintViolation}'s
     * own Javadoc for why a real {@code flag()} call can no longer be held open by an outer
     * {@code TransactionTemplate} wrapper now that it runs in its own {@code REQUIRES_NEW} transaction.
     */
    private void holdConflictingFlagRow(UUID reviewId, long flaggedBy, CountDownLatch rowHeld,
                                         CountDownLatch release, AtomicReference<Throwable> failure) {
        try {
            transactionTemplate.execute(status -> {
                jdbcTemplate.update(
                    "INSERT INTO reviews.review_flags (review_id, flagged_by, reason, created_at) " +
                    "VALUES (?, ?, 'CONFLICT_OF_INTEREST', ?)",
                    reviewId, flaggedBy, Timestamp.from(Instant.now()));
                rowHeld.countDown();
                try {
                    boolean released = release.await(30, TimeUnit.SECONDS);
                    if (!released) {
                        throw new AssertionError("release was never signalled within 30s");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
        } catch (Throwable t) {
            failure.set(t);
        }
    }

    private void insertUser(long id, String email, String role) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', ?, ?, 'Test', 'OTHER', 'en', 'User', 'DE', ?, " +
            "true, false, ?, 'EMAIL', 'x', false, " +
            "?, 'BASIC_VERIFIED')",
            id,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            Date.valueOf(LocalDate.of(1990, 1, 1)),
            email,
            "806" + (id % 10000000),
            email, role);
    }
}
