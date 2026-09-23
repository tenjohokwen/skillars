package com.softropic.skillars.platform.reviews.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.reviews.contract.ReviewFlagReason;
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
 * thread to hold {@code updateReview}'s own row lock open past its normal method boundary (by
 * calling it INSIDE an outer {@code TransactionTemplate.execute} and pausing before that lambda
 * returns) so a concurrent {@code flag()} call is forced to block on the SAME row lock rather than
 * proceeding on a stale unlocked read.
 */
class ReviewFlagServiceConcurrencyIT extends AbstractIntegrationTest {

    @Autowired private ReviewFlagService reviewFlagService;
    @Autowired private ReviewSubmissionService reviewSubmissionService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

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
            // Give the flagger thread a real chance to reach and block on the row lock before releasing.
            Thread.sleep(300);
            releaseUpdate.countDown();

            updater.get(30, TimeUnit.SECONDS);
            flagger.get(30, TimeUnit.SECONDS);

            if (updaterFailure.get() != null) {
                throw new AssertionError("updateReview thread failed", updaterFailure.get());
            }
            if (flaggerFailure.get() != null) {
                throw new AssertionError("flag() thread failed", flaggerFailure.get());
            }

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
            Thread.sleep(300);
            releaseLock.countDown();

            locker.get(30, TimeUnit.SECONDS);
            flagger.get(30, TimeUnit.SECONDS);

            if (lockerFailure.get() != null) {
                throw new AssertionError("locker thread failed", lockerFailure.get());
            }
            if (flaggerFailure.get() != null) {
                throw new AssertionError("flag() thread failed", flaggerFailure.get());
            }

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
