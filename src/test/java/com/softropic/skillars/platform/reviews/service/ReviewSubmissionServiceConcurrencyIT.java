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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-131 AC1 Fix 1. {@code review = coachReviewRepository.save(review)} inside
 * {@code submitReview}'s {@code try} never flushed (client-side {@code GenerationType.UUID}), so the
 * real {@code uq_coach_reviews_author_coach} violation from a genuine concurrent double-submit
 * surfaced uncommitted, outside the method, at whatever later flush point the transaction hit — never
 * inside the {@code catch (DataIntegrityViolationException e)} it was written for. Switching to
 * {@code saveAndFlush} forces the constraint check inside the existing {@code try}.
 *
 * <p><strong>Deliberately no outer {@code transactionTemplate.execute(...)} wrapper for the LOSING
 * call</strong> (pre-implementation {@code story-review.md} audit finding, mirroring {@code
 * CoachProfileServiceConcurrencyIT#concurrentPublish_exactlyOneSucceeds_loserGetsAlreadyPublishedNotGeneric400}'s
 * own shape exactly): {@code ReviewResource} opens no transaction in production, so {@code
 * ReviewSubmissionService}'s own {@code @Transactional} is always the outermost boundary. Wrapping the
 * loser's call in an outer {@code TransactionTemplate} would instead mark that outer transaction
 * rollback-only on the loser's flush-time {@code DataIntegrityViolationException}, surfacing {@code
 * UnexpectedRollbackException} at the outer boundary instead of the expected {@code
 * ALREADY_SUBMITTED} — a false failure that reads like the fix didn't work. (skillars-deferred-132 AC2
 * Fix 7 deliberately DOES prove the opposite case — an outer transaction surviving that same DIVE — in
 * {@link #concurrentSubmit_calledFromWithinAnOuterTransaction_loserStillGetsCleanAlreadySubmitted}
 * below, once {@code submitReview} itself runs in its own {@code REQUIRES_NEW} transaction.)
 *
 * <p><strong>skillars-deferred-132 AC2 Fix 7 correction:</strong> the row-holding mechanism below no
 * longer uses two real {@code submitReview} calls with the winner's own transaction held open via an
 * outer {@code transactionTemplate.execute(...)} wrapper — Fix 7 makes {@code submitReview} itself run
 * in its own {@code REQUIRES_NEW} transaction, which commits (and releases the winner's row) as soon as
 * {@code submitReview} returns, regardless of any enclosing transaction the caller wraps it in. That
 * silently defeated the old hold-open technique (confirmed empirically: the old test still passed, but
 * ~20x slower, because timing now decided which of two paths the loser hit — the exact
 * "scheduling-dependent shortcut" this test's own comment already warned about, now reachable a
 * different way). {@link #holdConflictingReviewRow} instead holds the conflicting unique-index entry
 * open from a plain {@code transactionTemplate.execute(...)} block that never calls
 * {@code submitReview} at all — a mechanism with no dependency on {@code submitReview}'s own
 * propagation setting, so it stays deterministic regardless of future changes to it.
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
     * A {@code submitReview} call racing a raw, independently-held conflicting
     * {@code coach_reviews} row (see {@link #holdConflictingReviewRow}) must get the clean
     * {@code ALREADY_SUBMITTED} the non-concurrent pre-check already throws, not an uncaught/generic
     * failure from an unmapped flush-time constraint violation surfacing outside this method.
     *
     * <p>Code review 2026-09-23 (original finding, still the reason this test forces the row-holding
     * mechanism rather than relying on natural thread scheduling): a plain latch-released race can pass
     * even on a REVERTED Fix 1. {@code submitReview}'s unlocked {@code existsByAuthorIdAndCoachId}
     * pre-check runs before the save — if the conflicting row were already visible/committed by the
     * time this call's pre-check runs, that pre-check alone would catch the duplicate directly, without
     * ever reaching {@code saveAndFlush}'s catch block this test exists to pin. Holding the conflicting
     * row's transaction open (uncommitted) until after this call has had time to reach its own
     * {@code saveAndFlush} forces it down the real flush-time path instead, where Postgres's unique
     * index blocks the second inserter until the first resolves.
     */
    @Test
    void concurrentSubmit_exactlyOnePersists_loserGetsAlreadySubmittedNotGeneric() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch rowHeld = new CountDownLatch(1);
            CountDownLatch releaseRow = new CountDownLatch(1);
            AtomicReference<Throwable> holderFailure = new AtomicReference<>();

            Future<?> holder = executor.submit(() ->
                holdConflictingReviewRow(coachProfileId, AUTHOR_ID, rowHeld, releaseRow, holderFailure));

            Callable<Throwable> submitTask = () -> {
                assertThat(rowHeld.await(10, TimeUnit.SECONDS)).isTrue();
                try {
                    reviewSubmissionService.submitReview(
                        coachProfileId, AUTHOR_ID, "PARENT", 4, "Also great!");
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            };
            Future<Throwable> submitter = executor.submit(submitTask);

            // submitReview's own pre-check + saveAndFlush attempt must have a chance to run (and block
            // on Postgres's unique index, uncontended by any lock of ours) before the held row's
            // transaction is released.
            Thread.sleep(300);
            releaseRow.countDown();

            holder.get(30, TimeUnit.SECONDS);
            Throwable submitFailure = submitter.get(30, TimeUnit.SECONDS);

            if (holderFailure.get() != null) {
                throw new AssertionError("row-holder thread failed", holderFailure.get());
            }
            assertThat(submitFailure)
                .as("the losing caller must get the same ALREADY_SUBMITTED code the non-concurrent "
                    + "pre-check throws, not a generic data-error from an unmapped flush-time violation")
                .isInstanceOf(OperationNotAllowedException.class);
            assertThat(((OperationNotAllowedException) submitFailure).getErrorCode())
                .isEqualTo(ReviewErrorCode.ALREADY_SUBMITTED);

            Integer reviewCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reviews.coach_reviews WHERE coach_id = ? AND author_id = ?",
                Integer.class, coachProfileId, AUTHOR_ID);
            assertThat(reviewCount).as("exactly one review row, never two").isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * skillars-deferred-132 AC2 Fix 7. Proves the actual point of this fix: {@code submitReview}
     * called from WITHIN a hypothetical outer transaction (something {@code ReviewResource} itself
     * never does today, but a future caller might) must not leave that outer transaction rollback-only
     * when {@code submitReview}'s own {@code saveAndFlush} hits the real unique-constraint violation.
     * Pre-Fix-7, the shared physical transaction (submitReview joining the outer one under the default
     * {@code REQUIRED} propagation) would already be marked rollback-only by the DIVE by the time this
     * test's own {@code transactionTemplate.execute(...)} tries to commit — surfacing
     * {@code UnexpectedRollbackException} right there, not inside {@code submitReview}'s own catch
     * block. Post-Fix-7, {@code submitReview}'s {@code REQUIRES_NEW} transaction is a fully separate
     * physical transaction, so the outer one it was called from is never touched by the inner failure.
     *
     * <p>The outer transaction touches an UNRELATED row ({@code main."user"}, not
     * {@code uq_coach_reviews_author_coach}) per Fix 7's own self-deadlock warning — an outer
     * transaction that touched the SAME unique key the inner {@code REQUIRES_NEW} call also targets
     * would hold an uncommitted conflicting entry on this SAME thread while the inner call blocks on
     * it, forever.
     */
    @Test
    void concurrentSubmit_calledFromWithinAnOuterTransaction_loserStillGetsCleanAlreadySubmitted() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch rowHeld = new CountDownLatch(1);
            CountDownLatch releaseRow = new CountDownLatch(1);
            AtomicReference<Throwable> holderFailure = new AtomicReference<>();

            Future<?> holder = executor.submit(() ->
                holdConflictingReviewRow(coachProfileId, AUTHOR_ID, rowHeld, releaseRow, holderFailure));

            assertThat(rowHeld.await(10, TimeUnit.SECONDS)).isTrue();

            AtomicReference<Throwable> submitFailure = new AtomicReference<>();
            AtomicReference<Throwable> outerTransactionFailure = new AtomicReference<>();
            Future<?> outerCaller = executor.submit(() -> {
                try {
                    transactionTemplate.execute(status -> {
                        jdbcTemplate.update(
                            "UPDATE main.\"user\" SET last_modified_date = now() WHERE id = ?", AUTHOR_ID);
                        try {
                            reviewSubmissionService.submitReview(
                                coachProfileId, AUTHOR_ID, "PARENT", 4, "Also great!");
                        } catch (OperationNotAllowedException e) {
                            // Deliberately caught HERE, inside the outer transaction, and NOT
                            // rethrown — mirroring how a future transactional caller would actually
                            // handle this business exception. Letting it propagate out of this lambda
                            // would itself abort the outer transaction for an unrelated reason (the
                            // caller's own exception, not a rollback-only Postgres transaction) and
                            // would not distinguish pre- from post-Fix-7 behavior.
                            submitFailure.set(e);
                        }
                        return null;
                    });
                } catch (Throwable t) {
                    // Pre-Fix-7, THIS is where UnexpectedRollbackException would surface — at the
                    // outer transaction's own commit, inside transactionTemplate.execute, not inside
                    // submitReview's own try/catch above.
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
                    + "UnexpectedRollbackException here means submitReview's REQUIRES_NEW isolation "
                    + "did not actually protect the outer caller")
                .isNull();
            assertThat(submitFailure.get())
                .as("submitReview must still translate the real unique-constraint violation to a clean "
                    + "business exception even when called from inside an outer transaction")
                .isInstanceOf(OperationNotAllowedException.class);
            assertThat(((OperationNotAllowedException) submitFailure.get()).getErrorCode())
                .isEqualTo(ReviewErrorCode.ALREADY_SUBMITTED);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * skillars-deferred-132 AC2 Fix 7. Holds a raw {@code coach_reviews} row — with the SAME
     * {@code (coach_id, author_id)} {@code submitReview} would insert — open (uncommitted) in its own
     * transaction until {@code release} counts down, then commits. Deliberately does NOT call
     * {@code submitReview} itself: with Fix 7 making {@code submitReview} run in its own
     * {@code REQUIRES_NEW} transaction, that method's own row now commits as soon as it returns
     * regardless of any enclosing transaction a caller wraps it in, so a "winner" built from a real
     * {@code submitReview} call can no longer be held open by an outer {@code TransactionTemplate}
     * wrapper the way it could before this story. This raw-SQL holder has no dependency on
     * {@code submitReview}'s own propagation setting at all.
     */
    private void holdConflictingReviewRow(UUID coachId, long authorId, CountDownLatch rowHeld,
                                           CountDownLatch release, AtomicReference<Throwable> failure) {
        try {
            transactionTemplate.execute(status -> {
                jdbcTemplate.update(
                    "INSERT INTO reviews.coach_reviews " +
                    "(review_id, coach_id, author_id, author_role, rating, body, moderation_status, " +
                    " moderation_epoch, created_at, last_modified_at) " +
                    "VALUES (?, ?, ?, 'PARENT', 5, 'Raw holder row', 'APPROVED', 0, ?, ?)",
                    UUID.randomUUID(), coachId, authorId,
                    Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
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
            "809" + (id % 10000000),
            email, role);
    }
}
