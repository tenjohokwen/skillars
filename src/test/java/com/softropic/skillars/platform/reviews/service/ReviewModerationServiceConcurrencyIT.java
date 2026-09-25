package com.softropic.skillars.platform.reviews.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.admin.service.AdminReviewService;
import com.softropic.skillars.platform.messaging.contract.ModerationVerdict;
import com.softropic.skillars.platform.reviews.contract.ReviewSubmittedEvent;
import com.softropic.skillars.utils.ConcurrencyLockWaitSupport;
import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-135 AC3: the REQUIRED dedicated concurrency test for {@code
 * ReviewModerationService.handleReviewSubmitted}'s own lock conversion (NOWAIT + {@code
 * PessimisticLockRetryer.withBoundedRetry}, replacing a genuinely blocking {@code
 * findByIdForUpdate}) — see {@code CoachReviewRepository.findByIdForUpdateNoWait}'s own Javadoc for
 * why this specific call site, unlike the other four this same story converts, needed its own
 * empirical confirmation before converting: its own pre-conversion comment warned the lock might need
 * to genuinely block, since the row can legitimately stay contended for the duration of an admin's
 * {@code approveReview}/{@code blockReview} transaction while this method's own Gemini call (which
 * runs OUTSIDE any transaction and can take seconds) is in flight.
 *
 * <h2>Two properties, two tests</h2>
 * <ol>
 *   <li>{@link #handleReviewSubmitted_underGenuineRawLockContention_stillAppliesVerdictOnceLockReleased}
 *       proves the NOWAIT+retry MECHANISM itself works for this call site — mirrors {@code
 *       ReviewFlagServiceConcurrencyIT#flagUnderGenuineLockContention_stillAppliesAutoHoldOnceThresholdReached}'s
 *       own raw-holder shape exactly.</li>
 *   <li>{@link #handleReviewSubmitted_racingConcurrentAdminBlock_adminDecisionWinsNotOverwrittenByStaleVerdict}
 *       proves the actual RISK the caveat named: a real, concurrent {@code AdminReviewService.blockReview}
 *       call landing while this method's own Gemini call is still "thinking" must not have its
 *       decision silently reverted by the moderation verdict once it finally lands — the existing
 *       PENDING-only guard (not the lock itself) is what protects this, and this test confirms that
 *       guard still holds under GENUINE concurrent execution against the real {@code AdminReviewService},
 *       not a synthetic direct-method-call race.</li>
 * </ol>
 */
class ReviewModerationServiceConcurrencyIT extends AbstractIntegrationTest {

    @Autowired private ReviewModerationService reviewModerationService;
    @Autowired private AdminReviewService adminReviewService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private MeterRegistry meterRegistry;

    private static final String MODERATION_LOCK_NAME = "ReviewModerationService.handleReviewSubmitted";

    private static final long AUTHOR_ID = 8070_000_001L;
    private static final long ADMIN_ID = 8070_000_099L;
    private static final long COACH_USER_ID = 8070_000_010L;

    private UUID coachProfileId;
    private UUID reviewId;

    @BeforeEach
    void setUp() {
        coachProfileId = UUID.randomUUID();
        reviewId = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            insertUser(AUTHOR_ID, "author.modrace@skillars-test.com", "PARENT");
            insertUser(COACH_USER_ID, "coach.modrace@skillars-test.com", "COACH");

            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Moderation Race Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID);

            jdbcTemplate.update(
                "INSERT INTO reviews.coach_reviews " +
                "(review_id, coach_id, author_id, author_role, rating, body, moderation_status, " +
                " moderation_epoch, created_at, last_modified_at) " +
                "VALUES (?, ?, ?, 'PARENT', 4, 'Great coach!', 'PENDING', 0, ?, ?)",
                reviewId, coachProfileId, AUTHOR_ID,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));

            return null;
        });
    }

    /**
     * Mirrors {@code ReviewFlagServiceConcurrencyIT}'s own raw-holder shape: a plain {@code
     * SELECT ... FOR UPDATE} (no {@code moderationStatus} change) genuinely holds the row lock while
     * {@code handleReviewSubmitted} contends for it. Proves the NOWAIT contender retries in Java
     * (not a live Postgres wait state — {@link ConcurrencyLockWaitSupport}'s own Javadoc explains why
     * a {@code pg_locks} poll cannot observe it) and still correctly applies the SAFE verdict once the
     * genuine holder releases.
     */
    @Test
    void handleReviewSubmitted_underGenuineRawLockContention_stillAppliesVerdictOnceLockReleased() throws Exception {
        when(geminiClient.evaluate(any())).thenReturn(ModerationVerdict.SAFE);

        double lockRetryBaseline = ConcurrencyLockWaitSupport.currentLockRetryCount(meterRegistry, MODERATION_LOCK_NAME);
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

            AtomicReference<Instant> moderationCompletedAt = new AtomicReference<>();
            Future<?> moderator = executor.submit(() -> {
                boolean held;
                try {
                    held = lockHeld.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                if (!held) {
                    throw new AssertionError("lockHeld was never signalled within 10s");
                }
                reviewModerationService.handleReviewSubmitted(
                    new ReviewSubmittedEvent(reviewId, coachProfileId, AUTHOR_ID, 4, "Great coach!", 0L));
                moderationCompletedAt.set(Instant.now());
            });

            assertThat(lockHeld.await(10, TimeUnit.SECONDS)).isTrue();
            ConcurrencyLockWaitSupport.awaitFirstLockAttempt();
            releaseLock.countDown();

            locker.get(30, TimeUnit.SECONDS);
            moderator.get(30, TimeUnit.SECONDS);

            if (lockerFailure.get() != null) {
                throw new AssertionError("locker thread failed", lockerFailure.get());
            }

            ConcurrencyLockWaitSupport.assertGenuineLockRetryOccurred(meterRegistry, MODERATION_LOCK_NAME, lockRetryBaseline);

            assertThat(moderationCompletedAt.get())
                .as("handleReviewSubmitted must not complete until the raw FOR UPDATE lock is "
                    + "released — proving it genuinely contended for the row, not merely ran uncontended")
                .isAfterOrEqualTo(lockReleasedAt.get());

            String moderationStatus = jdbcTemplate.queryForObject(
                "SELECT moderation_status FROM reviews.coach_reviews WHERE review_id = ?", String.class, reviewId);
            assertThat(moderationStatus)
                .as("once genuine lock contention resolves, the SAFE verdict must still be applied")
                .isEqualTo("APPROVED");
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * The REQUIRED test per this story's own AC3 caveat. {@code geminiClient.evaluate} is stubbed to
     * signal a latch the instant it is invoked, then sleep for {@code GEMINI_THINK_TIME} before
     * returning SAFE — modelling the real, documented shape ("the Gemini call above runs outside any
     * transaction and can take seconds, which is ample room for an admin to resolve the review in the
     * meantime"). {@code blockReview} is released only once that latch fires, guaranteeing it starts
     * (and, since it is a fast DB-only transaction with no external I/O, very likely finishes) WHILE
     * {@code handleReviewSubmitted} is still inside its own Gemini call, not yet holding the row lock.
     *
     * <p>This is deliberately NOT a raw-lock-holder test — it exercises the REAL {@code
     * AdminReviewService.blockReview} entry point end-to-end (its own now-NOWAIT+retry lock
     * acquisition included), racing the REAL {@code handleReviewSubmitted} entry point, to prove the
     * actual risk the caveat named: does the admin's decision survive a moderation verdict that was
     * "in flight" when the admin acted?
     */
    @Test
    void handleReviewSubmitted_racingConcurrentAdminBlock_adminDecisionWinsNotOverwrittenByStaleVerdict() throws Exception {
        long geminiThinkTimeMillis = 700;
        CountDownLatch geminiCallStarted = new CountDownLatch(1);
        when(geminiClient.evaluate(any())).thenAnswer(invocation -> {
            geminiCallStarted.countDown();
            Thread.sleep(geminiThinkTimeMillis);
            return ModerationVerdict.SAFE;
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> moderator = executor.submit(() ->
                reviewModerationService.handleReviewSubmitted(
                    new ReviewSubmittedEvent(reviewId, coachProfileId, AUTHOR_ID, 4, "Great coach!", 0L)));

            AtomicReference<Throwable> adminFailure = new AtomicReference<>();
            Future<?> admin = executor.submit(() -> {
                try {
                    boolean started = geminiCallStarted.await(10, TimeUnit.SECONDS);
                    if (!started) {
                        throw new AssertionError("geminiCallStarted was never signalled within 10s");
                    }
                    adminReviewService.blockReview(reviewId, "policy violation", ADMIN_ID);
                } catch (Throwable t) {
                    adminFailure.set(t);
                }
            });

            admin.get(30, TimeUnit.SECONDS);
            if (adminFailure.get() != null) {
                throw new AssertionError("admin blockReview thread failed", adminFailure.get());
            }
            // handleReviewSubmitted's own try/catch swallows everything, so this never throws — it
            // just needs to have fully run (including its own NOWAIT+retry attempts against the now
            // BLOCKED-and-committed row) before the assertions below read final state.
            moderator.get(30, TimeUnit.SECONDS);

            String moderationStatus = jdbcTemplate.queryForObject(
                "SELECT moderation_status FROM reviews.coach_reviews WHERE review_id = ?", String.class, reviewId);
            String heldReason = jdbcTemplate.queryForObject(
                "SELECT held_reason FROM reviews.coach_reviews WHERE review_id = ?", String.class, reviewId);

            assertThat(moderationStatus)
                .as("the admin's BLOCKED decision, committed while the moderation verdict was still "
                    + "'in flight' inside its own Gemini call, must not be silently reverted by that "
                    + "stale SAFE verdict once NOWAIT+retry finally lets it land — the existing "
                    + "PENDING-only guard must still discard it")
                .isEqualTo("BLOCKED");
            assertThat(heldReason)
                .as("blockReview clears heldReason to null; the discarded moderation verdict must not "
                    + "reintroduce a GEMINI_* reason")
                .isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    private void insertUser(long id, String email, String skillarsRole) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', '1985-06-01', ?, 'Moderation', 'OTHER', 'en', 'Race', 'DE', ?, " +
            "true, false, ?, 'EMAIL', 'hash', false, " +
            "?, 'BASIC_VERIFIED')",
            id,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email,
            "807" + (id % 100000000),
            email,
            skillarsRole
        );
    }
}
