package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.BasePaymentIT;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-23 AC6: {@code SessionPackPurchaseRepository.findByIdForUpdate}'s
 * {@code @Lock(PESSIMISTIC_WRITE)} must still serialize concurrent mutations after this story's change
 * (adding the sibling {@code @QueryHints} lock-timeout hint, removing the redundant method-level
 * {@code @Transactional}).
 *
 * <p><strong>Does not assert a bounded wait.</strong> Investigation during this story found that
 * Hibernate's {@code PostgreSQLDialect} only special-cases the {@code NO_WAIT}/{@code SKIP_LOCKED}
 * sentinels in {@code withTimeout(...)} — any finite {@code jakarta.persistence.lock.timeout} value,
 * including this repository's and its three siblings', falls through unchanged and has no effect on
 * Postgres. Confirmed empirically: a contended {@code findByIdForUpdate} call blocked for the full
 * duration a competing lock was held (tested up to 12s) and then completed normally, with no
 * {@code PessimisticLockingFailureException} ever raised. That gap is systemic to all four
 * {@code findByIdForUpdate} repositories, not specific to this one, and fixing it is out of this
 * story's scope — see the corresponding {@code deferred-work.md} entry filed alongside this story.
 *
 * <p>What this test <em>does</em> prove, and what would actually catch a regression: the
 * {@code PESSIMISTIC_WRITE} mutex itself. Without it, two concurrent {@link PackSessionService#deductSession}
 * calls on the same purchase can both load the same row, each decrement {@code remainingSessions} in
 * memory, and each attempt to save — but {@link com.softropic.skillars.platform.payment.repo.SessionPackPurchase}
 * carries an {@code @Version} column, so the race does not silently corrupt the count. Instead the
 * losing thread's save fails with an unhandled {@code ObjectOptimisticLockingFailureException}, which
 * surfaces through {@link Future#get()} below and fails this test before the final assertion ever runs.
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
