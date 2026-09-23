package com.softropic.skillars.platform.reviews.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.reviews.contract.ReviewErrorCode;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.sql.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-131 AC1 Fix 1. {@code review = coachReviewRepository.save(review)} inside
 * {@code submitReview}'s {@code try} never flushed (client-side {@code GenerationType.UUID}), so the
 * real {@code uq_coach_reviews_author_coach} violation from a genuine concurrent double-submit
 * surfaced uncommitted, outside the method, at whatever later flush point the transaction hit — never
 * inside the {@code catch (DataIntegrityViolationException e)} it was written for. Switching to
 * {@code saveAndFlush} forces the constraint check inside the existing {@code try}.
 *
 * <p><strong>Deliberately no outer {@code transactionTemplate.execute(...)} wrapper for either
 * call</strong> (pre-implementation {@code story-review.md} audit finding, mirroring {@code
 * CoachProfileServiceConcurrencyIT#concurrentPublish_exactlyOneSucceeds_loserGetsAlreadyPublishedNotGeneric400}'s
 * own shape exactly): {@code ReviewResource} opens no transaction in production, so {@code
 * ReviewSubmissionService}'s own {@code @Transactional} is always the outermost boundary. Wrapping the
 * loser's call in an outer {@code TransactionTemplate} would instead mark that outer transaction
 * rollback-only on the loser's flush-time {@code DataIntegrityViolationException}, surfacing {@code
 * UnexpectedRollbackException} at the outer boundary instead of the expected {@code
 * ALREADY_SUBMITTED} — a false failure that reads like the fix didn't work.
 */
class ReviewSubmissionServiceConcurrencyIT extends AbstractIntegrationTest {

    @Autowired private ReviewSubmissionService reviewSubmissionService;

    private static final long AUTHOR_ID = 8090_000_001L;
    private static final long COACH_USER_ID = 8090_000_010L;

    private UUID coachProfileId;

    @BeforeEach
    void setUp() {
        coachProfileId = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            insertUser(AUTHOR_ID, "author.submitrace@skillars-test.com", "PARENT");
            insertUser(COACH_USER_ID, "coach.submitrace@skillars-test.com", "COACH");

            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Submit Race Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID);

            long playerId = AUTHOR_ID + 1_000_000L;
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Submit Race Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                playerId, Date.valueOf(LocalDate.now().minusYears(18)),
                AUTHOR_ID, Timestamp.from(Instant.now()));

            // Eligibility for submitReview's own checkEligibility: a COMPLETED booking within the
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

            return null;
        });
    }

    /**
     * Two concurrent {@code submitReview} calls for the same {@code (coachId, authorId)}. Exactly one
     * must persist a {@code CoachReview} row; the loser must get the clean {@code ALREADY_SUBMITTED}
     * the non-concurrent pre-check already throws, not an uncaught/generic failure from an unmapped
     * flush-time constraint violation surfacing outside this method.
     *
     * <p>Code review 2026-09-23: a plain {@code CountDownLatch}-released race (the original shape here)
     * can pass even on a REVERTED Fix 1. {@code submitReview}'s unlocked
     * {@code existsByAuthorIdAndCoachId} pre-check runs before the save — if the OS/JVM happens to fully
     * serialize the two threads (the second task not dispatched until the first has already committed),
     * the second call's own pre-check catches the duplicate and throws {@code ALREADY_SUBMITTED}
     * directly, without ever reaching {@code saveAndFlush}'s catch block this test exists to pin. The
     * observable outcome (one success, one {@code ALREADY_SUBMITTED}, one row) is identical either way,
     * so that scheduling-dependent shortcut silently defeats the test. The winner's transaction is now
     * deliberately held open (uncommitted) past its own {@code submitReview} return, mirroring
     * {@code CoachProfileServiceConcurrencyIT}'s own hold-open technique, so the loser's unlocked
     * pre-check is guaranteed to run while the winner's row is still invisible (READ COMMITTED) — the
     * loser is thereby forced down the real {@code saveAndFlush} path, where Postgres's unique index
     * blocks it until the winner commits, deterministically producing the exact flush-time
     * {@code DataIntegrityViolationException} Fix 1 exists to catch.
     */
    @Test
    void concurrentSubmit_exactlyOnePersists_loserGetsAlreadySubmittedNotGeneric() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch winnerInsertedUncommitted = new CountDownLatch(1);
            CountDownLatch releaseWinner = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<Throwable> winnerFailure =
                new java.util.concurrent.atomic.AtomicReference<>();

            Future<?> winner = executor.submit(() -> {
                try {
                    transactionTemplate.execute(status -> {
                        reviewSubmissionService.submitReview(
                            coachProfileId, AUTHOR_ID, "PARENT", 5, "Great coach!");
                        winnerInsertedUncommitted.countDown();
                        try {
                            boolean released = releaseWinner.await(30, TimeUnit.SECONDS);
                            if (!released) {
                                throw new AssertionError("releaseWinner was never signalled within 30s");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return null;
                    });
                } catch (Throwable t) {
                    winnerFailure.set(t);
                }
            });

            Callable<Throwable> loserTask = () -> {
                assertThat(winnerInsertedUncommitted.await(10, TimeUnit.SECONDS)).isTrue();
                try {
                    reviewSubmissionService.submitReview(
                        coachProfileId, AUTHOR_ID, "PARENT", 4, "Also great!");
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            };
            Future<Throwable> loser = executor.submit(loserTask);

            // The loser's own pre-check + saveAndFlush attempt must have a chance to run (and block on
            // Postgres's unique index, uncontended by any lock of ours) before the winner's transaction
            // is released — same NOWAIT/blocking-distinction rationale as ConcurrencyLockWaitSupport,
            // just inlined here since this is a genuine blocking wait, not a NOWAIT retry loop.
            Thread.sleep(300);
            releaseWinner.countDown();

            winner.get(30, TimeUnit.SECONDS);
            Throwable loserFailure = loser.get(30, TimeUnit.SECONDS);

            if (winnerFailure.get() != null) {
                throw new AssertionError("winner thread failed", winnerFailure.get());
            }
            assertThat(loserFailure)
                .as("the losing caller must get the same ALREADY_SUBMITTED code the non-concurrent "
                    + "pre-check throws, not a generic data-error from an unmapped flush-time violation")
                .isInstanceOf(OperationNotAllowedException.class);
            assertThat(((OperationNotAllowedException) loserFailure).getErrorCode())
                .isEqualTo(ReviewErrorCode.ALREADY_SUBMITTED);

            Integer reviewCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reviews.coach_reviews WHERE coach_id = ? AND author_id = ?",
                Integer.class, coachProfileId, AUTHOR_ID);
            assertThat(reviewCount).as("exactly one review row, never two").isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private static final Throwable NULL_MARKER = new Throwable();

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
            "809" + (id % 10000000),
            email, role);
    }
}
