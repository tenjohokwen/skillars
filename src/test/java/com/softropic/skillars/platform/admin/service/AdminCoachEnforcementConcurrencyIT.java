package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.admin.contract.CoachReinstatedEvent;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.service.ReliabilityStrikeConfig;
import com.softropic.skillars.platform.config.service.ConfigService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-121 AC1: proves {@link AdminCoachEnforcementService#reinstateCoach} and {@code
 * #deleteStrike} no longer commit a decision computed from a stale pre-lock read of {@code
 * CoachProfile.status}.
 *
 * <h2>Why not a bare-{@code CountDownLatch} simultaneous-release race</h2>
 *
 * {@code ReliabilityStrikeConcurrencyIT}'s shape proves a fix for an <em>order-independent</em>
 * convergence: {@code issue()} reaches the same final state no matter which of two identical
 * concurrent calls wins, so a bare latch release is enough. {@code reinstateCoach} vs. a
 * concurrent status change is <em>order-dependent by design</em> — whichever transaction commits
 * last is correctly the one that should win, both before and after this fix, because Postgres row
 * locks make every writer's {@code UPDATE} block on a concurrent {@code SELECT ... FOR UPDATE}
 * regardless of whether the <em>reader</em> took a lock. A race with no controlled interleaving
 * cannot observe which read happened before or after the other transaction's commit, so it cannot
 * discriminate pre-fix from post-fix behaviour here.
 *
 * <h2>What actually discriminates</h2>
 *
 * Whether the method's <em>decision</em> — not just its final write — is computed from stale or
 * fresh data. These tests force a deterministic interleaving mirroring {@code
 * RescheduleServiceConcurrencyIT}'s holder-thread shape: a background transaction takes the row's
 * {@code FOR UPDATE} lock and, while still holding it, either commits a status change (reinstate
 * case) or inserts fresh strikes (delete-strike case). The contender's call is started only after
 * the holder's lock is confirmed held, so its first lock attempt is guaranteed to land while the
 * holder's transaction is still open.
 *
 * <p><strong>{@code findByIdForUpdate} does not literally block.</strong> It is issued {@code
 * NOWAIT} ({@code CoachProfileRepository#findByIdForUpdate}, {@code
 * jakarta.persistence.lock.timeout = 0}) and fails immediately on contention; {@link
 * com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer} is what makes the
 * caller wait, via a ~3.2s jittered retry budget (8 attempts, 100ms→800ms×1.6, same budget {@code
 * RescheduleServiceConcurrencyIT} documents at its own brief-contention test). "Post-fix, the
 * contender waits out the holder" is therefore only true because {@code holdMillis} below is kept
 * safely inside that budget — this is the same non-literal-blocking caveat every other
 * {@code PessimisticLockRetryer} concurrency IT in this codebase carries, not something specific
 * to this test.
 *
 * <p><strong>Mutation check:</strong> revert either fix back to a plain {@code
 * coachProfileRepository.findById(coachId)} and its test fails deterministically, not flakily —
 * the contender's read is guaranteed (via the latch) to execute while the holder's transaction is
 * still open, so the stale decision is reproduced every run: {@code reinstateCoach}'s test then
 * observes a second {@link CoachReinstatedEvent} and a second {@code admin_action_log} row with
 * {@code action_type = 'COACH_REINSTATE'} for a coach that was already {@code ACTIVE}; {@code
 * deleteStrike}'s test then observes a wrongful revert to {@code ACTIVE} on a stale, no-longer-valid
 * strike count.
 */
@Import(AdminCoachEnforcementConcurrencyIT.ReinstateEventCapture.class)
class AdminCoachEnforcementConcurrencyIT extends AbstractIntegrationTest {

    @Autowired AdminCoachEnforcementService enforcementService;
    @Autowired CoachProfileRepository coachProfileRepository;
    @Autowired ConfigService configService;
    @Autowired ReinstateEventCapture eventCapture;

    private static final long COACH_USER_ID = 96501L;
    private static final long ADMIN_ID = 96502L;

    private UUID coachProfileId;

    @BeforeEach
    void setUp() {
        eventCapture.clear();
        transactionTemplate.execute(status -> {
            insertCoachUser(COACH_USER_ID, "enforcement.concurrency.coach@skillars-test.com");

            coachProfileId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status, status_changed_at) " +
                "VALUES (?, ?, 'Enforcement Concurrency Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'PENDING_REVIEW', ?)",
                coachProfileId, COACH_USER_ID, Timestamp.from(Instant.now()));
            return null;
        });
    }

    private void insertCoachUser(long id, String email) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', '1985-06-01', ?, 'Concurrency', 'OTHER', 'en', 'Coach', 'DE', ?, " +
            "true, false, ?, 'EMAIL', 'hash', false, " +
            "'COACH', 'BASIC_VERIFIED')",
            id,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email,
            "70" + (id % 100000000),
            email
        );
    }

    /**
     * Holder commits {@code ACTIVE} while still holding the row's {@code FOR UPDATE} lock;
     * contender's {@code reinstateCoach} call starts only once that lock is confirmed held.
     * Post-fix, the contender must retry through the holder's hold, then observe {@code ACTIVE}
     * and no-op via its own {@code status == ACTIVE} early return — no duplicate event, no
     * duplicate action-log row.
     */
    @Test
    @Timeout(45)
    void reinstateCoach_contendsWithConcurrentStatusChange_actsOnFreshNotStaleState() throws Exception {
        long holdMillis = 1200;
        CountDownLatch lockHeld = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> transactionTemplate.execute(status -> {
                jdbcTemplate.query(
                    "SELECT id FROM marketplace.coach_profiles WHERE id = ? FOR UPDATE",
                    rs -> { }, coachProfileId);
                lockHeld.countDown();
                try {
                    Thread.sleep(holdMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                jdbcTemplate.update(
                    "UPDATE marketplace.coach_profiles SET status = 'ACTIVE', status_changed_at = ? WHERE id = ?",
                    Timestamp.from(Instant.now()), coachProfileId);
                return null;
            }));

            assertThat(lockHeld.await(5, TimeUnit.SECONDS)).as("holder must acquire the lock first").isTrue();

            Instant start = Instant.now();
            Future<?> contender = pool.submit(() ->
                enforcementService.reinstateCoach(coachProfileId, "test", ADMIN_ID));
            long elapsedMillis;
            try {
                contender.get(20, TimeUnit.SECONDS);
                // Measured here, before holder.get() below — not after — so a slow-to-join holder
                // (or, in the prolonged-contention sibling test, one that is still sleeping well
                // past the contender's own retry-exhaustion) never inflates the contender's own
                // elapsed time.
                elapsedMillis = Duration.between(start, Instant.now()).toMillis();
            } finally {
                // Always joined, even if the contender above failed unexpectedly — an un-joined
                // holder can still hold the row lock past this test method's return and block
                // DatabaseResetTestExecutionListener's TRUNCATE (needs ACCESS EXCLUSIVE) in the
                // next test.
                holder.get(15, TimeUnit.SECONDS);
            }

            assertThat(elapsedMillis)
                .as("contender must have genuinely retried through the holder's %dms hold, not raced past it", holdMillis)
                .isGreaterThanOrEqualTo(holdMillis - 200);
        } finally {
            pool.shutdownNow();
        }

        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, coachProfileId);
        assertThat(status).isEqualTo("ACTIVE");

        assertThat(eventCapture.forCoach(coachProfileId))
            .as("reinstateCoach must not act on the stale pre-lock PENDING_REVIEW read once the "
                + "holder has already committed ACTIVE")
            .isEmpty();

        Long reinstateLogCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin.admin_action_log WHERE reference_id = ? AND action_type = 'COACH_REINSTATE'",
            Long.class, coachProfileId.toString());
        assertThat(reinstateLogCount)
            .as("no admin_action_log row for a reinstate that should have been a no-op against fresh ACTIVE state")
            .isEqualTo(0L);
    }

    /**
     * A competing lock held past {@code PessimisticLockRetryer}'s retry budget must still surface
     * the contention as {@link PessimisticLockingFailureException}, bounded well before the full
     * hold time, and must not have partially applied the delete: the strike row (already flushed
     * by {@code strikeRepository.deleteById}, per Task 2's ordering) must be rolled back along
     * with the rest of the transaction, and the coach's status must be untouched. Mirrors {@code
     * RescheduleServiceConcurrencyIT#acceptReschedule_prolongedContentionOnRescheduleRequestRow_failsWithBoundedPessimisticLockingFailure}.
     */
    @Test
    @Timeout(45)
    void deleteStrike_prolongedContentionOnCoachRow_failsWithBoundedPessimisticLockingFailureAndRollsBack() throws Exception {
        UUID strikeToDelete = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            seedStrike(strikeToDelete);
            return null;
        });

        long holdMillis = 8000;
        CountDownLatch lockHeld = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> transactionTemplate.execute(status -> {
                jdbcTemplate.query(
                    "SELECT id FROM marketplace.coach_profiles WHERE id = ? FOR UPDATE",
                    rs -> { }, coachProfileId);
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
                enforcementService.deleteStrike(coachProfileId, strikeToDelete, "test", ADMIN_ID);
                return null;
            });
            long elapsedMillis;
            try {
                assertThatContenderFailsWithPessimisticLockingFailure(contender);
                // Measured here, before holder.get() below — the holder is still asleep for
                // several more seconds at this point (holdMillis=8000 vs. the ~3.2s retry
                // budget), and joining it first would fold its remaining sleep into the
                // contender's own elapsed time, defeating the bounded-exhaustion assertion below.
                elapsedMillis = Duration.between(start, Instant.now()).toMillis();
            } finally {
                holder.get(15, TimeUnit.SECONDS);
            }

            assertThat(elapsedMillis)
                .as("retry budget exhaustion must be bounded, well under the %dms hold time", holdMillis)
                .isLessThan(4500);
            assertThat(elapsedMillis)
                .as("must have genuinely retried, not failed on the very first attempt")
                .isGreaterThan(1000);
        } finally {
            pool.shutdownNow();
        }

        Long strikeCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM marketplace.coach_reliability_strikes WHERE id = ?", Long.class, strikeToDelete);
        assertThat(strikeCount)
            .as("the already-flushed strike DELETE must roll back with the rest of the transaction on lock exhaustion")
            .isEqualTo(1L);

        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, coachProfileId);
        assertThat(status)
            .as("a failed delete-strike attempt must not have partially applied a status change")
            .isEqualTo("PENDING_REVIEW");
    }

    private void assertThatContenderFailsWithPessimisticLockingFailure(Future<?> contender)
            throws InterruptedException, TimeoutException {
        try {
            contender.get(20, TimeUnit.SECONDS);
            throw new AssertionError("expected deleteStrike to fail with PessimisticLockingFailureException");
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(PessimisticLockingFailureException.class);
        }
    }

    /**
     * {@code deleteStrike}'s revert-to-{@code ACTIVE} path has the identical stale-vs-fresh shape
     * as {@code reinstateCoach}, gated by the rolling strike count instead of a direct status
     * check. Concurrent strikes push the count back above {@code visibilityThreshold} while {@code
     * deleteStrike} is in flight; pre-fix, a stale count read (taken before the lock) wrongly
     * reverts the coach to {@code ACTIVE} anyway. Post-fix, the locked read happens before the
     * count computation, so the fresh, post-lock count correctly leaves the coach un-reverted.
     */
    @Test
    @Timeout(45)
    void deleteStrike_concurrentStrikesPushCountAboveThreshold_doesNotRevertOnStaleCount() throws Exception {
        long visibilityThreshold = configService.getBoundedLong(
            ReliabilityStrikeConfig.VISIBILITY_THRESHOLD_KEY, ReliabilityStrikeConfig.DEFAULT_VISIBILITY_THRESHOLD, 1L, Long.MAX_VALUE);

        UUID strikeToDelete = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            // One strike below the threshold, plus the strike this test deletes -- so after the
            // delete, count = visibilityThreshold - 1 unless the holder's concurrent inserts land.
            for (long i = 0; i < visibilityThreshold - 1; i++) {
                seedStrike(UUID.randomUUID());
            }
            seedStrike(strikeToDelete);
            return null;
        });

        long holdMillis = 1200;
        CountDownLatch lockHeld = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> transactionTemplate.execute(status -> {
                jdbcTemplate.query(
                    "SELECT id FROM marketplace.coach_profiles WHERE id = ? FOR UPDATE",
                    rs -> { }, coachProfileId);
                lockHeld.countDown();
                try {
                    Thread.sleep(holdMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                // Push the rolling count back above the threshold while the lock is still held.
                seedStrike(UUID.randomUUID());
                seedStrike(UUID.randomUUID());
                return null;
            }));

            assertThat(lockHeld.await(5, TimeUnit.SECONDS)).as("holder must acquire the lock first").isTrue();

            Instant start = Instant.now();
            Future<?> contender = pool.submit(() ->
                enforcementService.deleteStrike(coachProfileId, strikeToDelete, "test", ADMIN_ID));
            long elapsedMillis;
            try {
                contender.get(20, TimeUnit.SECONDS);
                elapsedMillis = Duration.between(start, Instant.now()).toMillis();
            } finally {
                holder.get(15, TimeUnit.SECONDS);
            }

            assertThat(elapsedMillis)
                .as("contender must have genuinely retried through the holder's %dms hold, not raced past it", holdMillis)
                .isGreaterThanOrEqualTo(holdMillis - 200);
        } finally {
            pool.shutdownNow();
        }

        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, coachProfileId);
        assertThat(status)
            .as("must not revert to ACTIVE on a stale pre-lock count once the holder's concurrent "
                + "strikes have pushed the fresh count back above threshold")
            .isEqualTo("PENDING_REVIEW");

        Long strikeCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM marketplace.coach_reliability_strikes WHERE id = ?", Long.class, strikeToDelete);
        assertThat(strikeCount)
            .as("the targeted strike must still have been deleted regardless of the revert decision")
            .isEqualTo(0L);

        Long deletedLogCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin.admin_action_log WHERE reference_id = ? AND action_type = 'COACH_STRIKE_DELETED'",
            Long.class, coachProfileId.toString());
        assertThat(deletedLogCount)
            .as("the no-revert branch must log COACH_STRIKE_DELETED, not COACH_REINSTATE")
            .isEqualTo(1L);
    }

    private void seedStrike(UUID strikeId) {
        jdbcTemplate.update(
            "INSERT INTO marketplace.coach_reliability_strikes (id, coach_id, booking_id, reason, acknowledged, created_at) " +
            "VALUES (?, ?, ?, 'COACH_NO_SHOW', false, ?)",
            strikeId, coachProfileId, UUID.randomUUID(),
            Timestamp.from(Instant.now().minusSeconds(3600)));
    }

    @Component
    static class ReinstateEventCapture {
        private final List<CoachReinstatedEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        void on(CoachReinstatedEvent event) {
            events.add(event);
        }

        List<CoachReinstatedEvent> forCoach(UUID coachId) {
            return events.stream().filter(e -> coachId.equals(e.getCoachId())).toList();
        }

        void clear() {
            events.clear();
        }
    }
}
