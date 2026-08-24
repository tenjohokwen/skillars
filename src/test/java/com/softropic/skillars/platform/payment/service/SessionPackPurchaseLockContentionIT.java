package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.payment.BasePaymentIT;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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

/**
 * skillars-deferred-23 AC6: {@code SessionPackPurchaseRepository.findByIdForUpdate}'s
 * {@code @Lock(PESSIMISTIC_WRITE)} must still serialize concurrent mutations after this story's change
 * (adding the sibling {@code @QueryHints} lock-timeout hint, removing the redundant method-level
 * {@code @Transactional}).
 *
 * <p><strong>Now also asserts the bounded wait</strong> (skillars-deferred-62). The
 * {@code jakarta.persistence.lock.timeout} hint above was ineffective on Postgres — confirmed
 * empirically during deferred-62 the same way deferred-23 first found it: a contended
 * {@code findByIdForUpdate} call blocked for the full duration a competing lock was held (tested up
 * to 12s) and completed normally, with no {@code PessimisticLockingFailureException} ever raised.
 * deferred-62 replaced the hint with {@code NO_WAIT} (value {@code "0"}, the one sentinel
 * {@code PostgreSQLDialect.withTimeout(...)} actually honors) plus {@link PessimisticLockRetryer},
 * which retries a {@code NO_WAIT} failure from a JDBC savepoint token so the surrounding transaction
 * doesn't need to restart. {@link #deductSession_briefContention_succeedsAfterBoundedRetry()} and
 * {@link #deductSession_prolongedContention_failsWithBounded409AfterRetryBudgetExhausted()} below
 * cover that behavior for this repository; the other three {@code findByIdForUpdate} repositories
 * share the identical {@link PessimisticLockRetryer} helper, so per deferred-62 AC3 they rely on this
 * coverage rather than each duplicating a full concurrency IT.
 *
 * <p>What the original test in this class <em>does</em> prove, and what would actually catch a
 * regression: the {@code PESSIMISTIC_WRITE} mutex itself. Without it, two concurrent
 * {@link PackSessionService#deductSession} calls on the same purchase can both load the same row,
 * each decrement {@code remainingSessions} in memory, and each attempt to save — but
 * {@link com.softropic.skillars.platform.payment.repo.SessionPackPurchase} carries an {@code @Version}
 * column, so the race does not silently corrupt the count. Instead the losing thread's save fails
 * with an unhandled {@code ObjectOptimisticLockingFailureException}, which surfaces through
 * {@link Future#get()} below and fails this test before the final assertion ever runs.
 * Mutation-verified: removing {@code @Lock(PESSIMISTIC_WRITE)} from the repository method reproduces
 * that failure under this test's concurrency (confirmed twice; both runs errored at {@code f.get()},
 * not at the assertion).
 */
class SessionPackPurchaseLockContentionIT extends BasePaymentIT {

    @Autowired
    PackSessionService packSessionService;

    private UUID coachId;

    @BeforeEach
    void setUpCoach() {
        coachId = insertTestCoach(95201L, "lock_contention_coach@test.com", "Lock Contention Coach");
    }

    /**
     * A competing lock held well within {@link PessimisticLockRetryer}'s retry budget (~3.2s across
     * its default 8 attempts) must not surface a 409 — the retry loop should absorb it and the
     * deduction should still land, exactly as it would today with no contention at all.
     */
    @Test
    @Timeout(30)
    void deductSession_briefContention_succeedsAfterBoundedRetry() throws Exception {
        UUID purchaseId = insertTestPurchase(coachId, 5);
        long holdMillis = 1200;
        CountDownLatch lockHeld = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> transactionTemplate.execute(status -> {
                jdbcTemplate.query(
                    "SELECT purchase_id FROM payment.session_pack_purchases WHERE purchase_id = ? FOR UPDATE",
                    rs -> { }, purchaseId);
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
            Future<?> contender = pool.submit(() -> {
                packSessionService.deductSession(purchaseId);
                return null;
            });
            contender.get(20, TimeUnit.SECONDS);
            long elapsedMillis = Duration.between(start, Instant.now()).toMillis();
            holder.get(15, TimeUnit.SECONDS);

            assertThat(elapsedMillis)
                .as("must have actually waited out the brief contention via retry, not skipped it")
                .isGreaterThanOrEqualTo(holdMillis - 200);

            Integer remaining = jdbcTemplate.queryForObject(
                "SELECT remaining_sessions FROM payment.session_pack_purchases WHERE purchase_id = ?",
                Integer.class, purchaseId);
            assertThat(remaining)
                .as("brief contention must not turn into a lost deduction or a surfaced 409")
                .isEqualTo(4);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * A competing lock held past {@link PessimisticLockRetryer}'s retry budget must still surface the
     * contention as {@code PessimisticLockingFailureException} — the same exception type
     * {@code ApiAdvice.pessimisticLockExceptionHandler} already maps to a 409 — and must do so bounded
     * (well before the full hold time), not after an unbounded wait.
     */
    @Test
    @Timeout(30)
    void deductSession_prolongedContention_failsWithBounded409AfterRetryBudgetExhausted() throws Exception {
        UUID purchaseId = insertTestPurchase(coachId, 5);
        long holdMillis = 8000;
        CountDownLatch lockHeld = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> transactionTemplate.execute(status -> {
                jdbcTemplate.query(
                    "SELECT purchase_id FROM payment.session_pack_purchases WHERE purchase_id = ? FOR UPDATE",
                    rs -> { }, purchaseId);
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
            Future<?> contender = pool.submit(() -> {
                packSessionService.deductSession(purchaseId);
                return null;
            });

            assertThatThrownByContenderCompletesWithPessimisticLockingFailure(contender);
            long elapsedMillis = Duration.between(start, Instant.now()).toMillis();
            holder.get(15, TimeUnit.SECONDS);

            assertThat(elapsedMillis)
                .as("retry budget exhaustion must be bounded, well under the %dms hold time", holdMillis)
                .isLessThan(4500);
            assertThat(elapsedMillis)
                .as("must have genuinely retried, not failed on the very first attempt")
                .isGreaterThan(1000);

            Integer remaining = jdbcTemplate.queryForObject(
                "SELECT remaining_sessions FROM payment.session_pack_purchases WHERE purchase_id = ?",
                Integer.class, purchaseId);
            assertThat(remaining)
                .as("the failed attempt must not have partially applied the deduction")
                .isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertThatThrownByContenderCompletesWithPessimisticLockingFailure(Future<?> contender)
            throws InterruptedException, TimeoutException {
        try {
            contender.get(20, TimeUnit.SECONDS);
            throw new AssertionError("expected deductSession to fail with PessimisticLockingFailureException");
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(PessimisticLockingFailureException.class);
        }
    }

    @Test
    @Timeout(45)
    void deductSession_concurrentCalls_serializeWithoutLostUpdate() throws Exception {
        UUID purchaseId = insertTestPurchase(coachId, 5);

        int threadCount = 2;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    packSessionService.deductSession(purchaseId);
                    return null;
                }));
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS))
                .as("both deductSession calls must complete within 30 seconds")
                .isTrue();

            for (Future<Void> f : futures) {
                f.get();
            }
        } finally {
            // If awaitTermination times out or the barrier fails, don't leave worker threads (and
            // whatever DB row lock one may hold) running past this test — see class javadoc for why
            // an unlocked race is expected to fail here rather than reach the assertion below.
            pool.shutdownNow();
        }

        Integer remaining = jdbcTemplate.queryForObject(
            "SELECT remaining_sessions FROM payment.session_pack_purchases WHERE purchase_id = ?",
            Integer.class, purchaseId);

        assertThat(remaining)
            .as("findByIdForUpdate's PESSIMISTIC_WRITE lock must serialize concurrent deductions to "
                + "exactly 3 — if the lock were missing, the losing thread's "
                + "ObjectOptimisticLockingFailureException would already have failed this test at "
                + "f.get() above, so reaching this assertion at all is itself part of the proof")
            .isEqualTo(3);
    }

    private UUID insertTestPurchase(UUID forCoachId, int remainingSessions) {
        UUID tierId = UUID.randomUUID();
        UUID purchaseId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(30, ChronoUnit.DAYS);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO payment.session_pack_tiers " +
                "(pack_tier_id, coach_id, label, session_count, total_price, price_per_session, is_active, version, created_at) " +
                "VALUES (?, ?, '5-Pack', 5, 150.00, 30.00, true, 0, now())",
                tierId, forCoachId
            );
            jdbcTemplate.update(
                "INSERT INTO payment.session_pack_purchases " +
                "(purchase_id, parent_id, player_id, coach_id, pack_tier_id, price_per_session, remaining_sessions, expires_at, version, created_at) " +
                "VALUES (?, 95301, 95302, ?, ?, 30.00, ?, ?, 0, now())",
                purchaseId, forCoachId, tierId, remainingSessions, Timestamp.from(expiresAt)
            );
            return null;
        });
        return purchaseId;
    }
}
