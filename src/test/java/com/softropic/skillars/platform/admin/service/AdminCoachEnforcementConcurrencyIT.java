package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.admin.contract.CoachReinstatedEvent;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import com.softropic.skillars.platform.marketplace.contract.MarketplaceException;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.service.ReliabilityStrikeConfig;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.utils.CoachProfileTestFixtures;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

            // skillars-deferred-131 AC1 Fix 3: reinstateCoach/deleteStrike's tier-3 branch now
            // re-validate the profile is publishable before writing ACTIVE, so this coach needs
            // complete builder-step data for the tests that revert/reinstate it to ACTIVE.
            CoachProfileTestFixtures.seedCompleteBuilderSteps(jdbcTemplate, coachProfileId);
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
     * check. Concurrent strikes push the count back above {@code suspensionThreshold} while {@code
     * deleteStrike} is in flight; pre-fix, a stale count read (taken before the lock) wrongly
     * reverts the coach to {@code ACTIVE} anyway. Post-fix, the locked read happens before the
     * count computation, so the fresh, post-lock count correctly leaves the coach un-reverted.
     * <p>
     * skillars-deferred-122 AC1: redesigned to seed the holder's concurrent inserts so the fresh
     * post-delete count lands {@code >= suspensionThreshold}, not merely {@code >= visibilityThreshold}
     * — under the AC1-corrected three-tier tiering, a fresh count that lands inside the
     * {@code [visibilityThreshold, suspensionThreshold)} band is a <em>correct</em> {@code REDUCED}
     * transition, not "no status change". The original seeding (holder inserts 2, landing fresh
     * count at 4 — inside the new {@code REDUCED} band regardless of tiering correctness) no longer
     * discriminates a fresh read from a stale one under the corrected tiering.
     * <p>
     * Code review 2026-09-18: the pre-delete seed is {@code visibilityThreshold - 1} (= 2) plus the
     * strike being deleted (= 3 total, unaffected by the delete's own removal — the deleted strike
     * isn't counted either way), so the fresh count after the holder's concurrent inserts is
     * {@code 2 + N}, not {@code 3 + N} as an earlier version of this Javadoc claimed. 4 concurrent
     * inserts (not 3) land the fresh count at 6 — genuinely {@code >} {@code suspensionThreshold(5)},
     * not sitting exactly on it — so this test does not depend on {@code >=} specifically and survives
     * a future threshold-comparison or default-value change without failing for reasons unrelated to
     * the freshness property under test.
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
                // Push the rolling count back above suspensionThreshold while the lock is still
                // held (4 inserts, not 2 — see this test's Javadoc for the corrected arithmetic and
                // for why 2 no longer discriminates fresh-vs-stale under AC1's corrected three-tier
                // tiering).
                seedStrike(UUID.randomUUID());
                seedStrike(UUID.randomUUID());
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

    /**
     * Code review 2026-09-18 (ManualStrikeIT gap): {@code ManualStrikeIT}'s AC2 coverage issues two
     * <em>sequential</em> DELETEs, which only exercises the ownership-check {@code
     * ResourceNotFoundException} — by the second call, the strike is simply gone, so it 404s before
     * {@code deleteByIdAndCoachId} is ever reached. Exercising the actual {@code deletedRows == 0}
     * branch needs two callers that have <em>both already passed</em> the ownership check before
     * either commits — a genuine concurrent race, not a sequential replay.
     * <p>
     * A holder thread takes the strike row's {@code FOR UPDATE} lock and releases it (via commit,
     * changing nothing) only after both contenders' {@code strikeRepository.findById} ownership
     * checks — a plain {@code SELECT}, never blocked by another session's row lock under READ
     * COMMITTED — have had time to run and see the strike as still present. Both contenders then
     * block on the strike row's lock inside {@code deleteByIdAndCoachId}'s bulk {@code DELETE}.
     * Postgres serialises the two blocked DELETEs against each other: the winner's DELETE affects 1
     * row and its whole transaction (including the coach-profile lock/status logic downstream) commits
     * before the loser's blocked DELETE is even granted the lock, so the loser's {@code DELETE}
     * evaluates its {@code WHERE} clause against a row that is already gone — {@code deletedRows == 0}
     * — and {@code deleteStrike} must translate that into {@link ResourceNotFoundException}, not let
     * the now-defunct {@code StaleStateException} the old entity-based {@code deleteById} threw for a
     * 0-row delete escape as an unhandled 500.
     */
    @Test
    @Timeout(45)
    void deleteStrike_concurrentDuplicateDelete_loserGets404NotStaleStateException() throws Exception {
        UUID strikeToDelete = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            seedStrike(strikeToDelete);
            return null;
        });

        long holdMillis = 1200;
        CountDownLatch lockHeld = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> holder = pool.submit(() -> transactionTemplate.execute(status -> {
                jdbcTemplate.query(
                    "SELECT id FROM marketplace.coach_reliability_strikes WHERE id = ? FOR UPDATE",
                    rs -> { }, strikeToDelete);
                lockHeld.countDown();
                try {
                    Thread.sleep(holdMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                // Deliberately no write here — this thread exists only to hold the row lock long
                // enough for both contenders' ownership checks to land before either contender's
                // DELETE is granted the lock, then releases it unchanged via commit.
                return null;
            }));

            assertThat(lockHeld.await(5, TimeUnit.SECONDS)).as("holder must acquire the lock first").isTrue();
            // Give both contenders' pre-lock ownership-check SELECTs (unblocked by the holder's FOR
            // UPDATE) a moment to actually run before the holder releases the row.
            Thread.sleep(200);

            Future<?> contenderA = pool.submit(() -> {
                enforcementService.deleteStrike(coachProfileId, strikeToDelete, "first", ADMIN_ID);
                return null;
            });
            Future<?> contenderB = pool.submit(() -> {
                enforcementService.deleteStrike(coachProfileId, strikeToDelete, "second", ADMIN_ID);
                return null;
            });

            int successCount = 0;
            ResourceNotFoundException loserCause = null;
            for (Future<?> contender : List.of(contenderA, contenderB)) {
                try {
                    contender.get(20, TimeUnit.SECONDS);
                    successCount++;
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(ResourceNotFoundException.class);
                    loserCause = (ResourceNotFoundException) e.getCause();
                }
            }
            holder.get(15, TimeUnit.SECONDS);

            assertThat(successCount).as("exactly one of the two concurrent deletes must succeed").isEqualTo(1);
            assertThat(loserCause)
                .as("the loser must see ResourceNotFoundException (404), not an unhandled StaleStateException")
                .isNotNull();
        } finally {
            pool.shutdownNow();
        }

        Long strikeCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM marketplace.coach_reliability_strikes WHERE id = ?", Long.class, strikeToDelete);
        assertThat(strikeCount).as("the strike must have been deleted exactly once").isEqualTo(0L);
    }

    /**
     * skillars-deferred-131 AC1 Fix 3: {@code reinstateCoach} must fail loudly on an incomplete
     * profile rather than silently activating it — the profile this test's own coach starts with is
     * complete (seeded by {@link #setUp}), so this test explicitly removes the pricing step to
     * simulate a coach who never finished the builder.
     */
    @Test
    void reinstateCoach_incompleteProfile_fallsBackToDraftNotSilentActivation() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM marketplace.coach_pricing WHERE coach_id = ?", coachProfileId);
            return null;
        });

        // Code review 2026-09-23 (Decision 2): a thrown MarketplaceException here used to propagate
        // uncaught, rolling back the whole transaction and leaving the coach permanently stuck
        // PENDING_REVIEW with no admin path back to any status (saveStep4's own SUSPENDED-only guard
        // does not even apply to PENDING_REVIEW). reinstateCoach now catches it and falls back to
        // DRAFT instead of throwing, returning the coach to a self-serviceable onboarding state.
        enforcementService.reinstateCoach(coachProfileId, "test", ADMIN_ID);

        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, coachProfileId);
        assertThat(status).as("an incomplete profile falls back to DRAFT, not ACTIVE").isEqualTo("DRAFT");

        Integer subscriptionCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM marketplace.coach_subscriptions WHERE coach_id = ?", Integer.class, coachProfileId);
        assertThat(subscriptionCount).as("no subscription row for a reinstate that never actually activated").isZero();
    }

    /**
     * skillars-deferred-131 AC1 Fix 3: a complete profile with no pre-existing subscription row gets
     * a fresh SCOUT-tier row on reinstate, mirroring {@code publishProfile}'s own find-or-create.
     */
    @Test
    void reinstateCoach_completeProfileNoSubscription_createsScoutTierRow() {
        enforcementService.reinstateCoach(coachProfileId, "test", ADMIN_ID);

        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, coachProfileId);
        assertThat(status).isEqualTo("ACTIVE");

        String tier = jdbcTemplate.queryForObject(
            "SELECT tier FROM marketplace.coach_subscriptions WHERE coach_id = ?", String.class, coachProfileId);
        assertThat(tier).isEqualTo(CoachSubscriptionTier.SCOUT.name());
    }

    /**
     * skillars-deferred-131 AC1 Fix 3: an existing subscription row's tier must survive reinstate
     * untouched, not be reset to SCOUT — mirrors {@code publishProfile}'s own preserve-not-overwrite
     * rationale for a coach who already holds a paid tier from a purchase made while off the
     * marketplace.
     */
    @Test
    void reinstateCoach_existingSubscription_preservesExistingTier() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_subscriptions (coach_id, tier, active_since) VALUES (?, 'INSTRUCTOR', now())",
                coachProfileId);
            return null;
        });

        enforcementService.reinstateCoach(coachProfileId, "test", ADMIN_ID);

        String tier = jdbcTemplate.queryForObject(
            "SELECT tier FROM marketplace.coach_subscriptions WHERE coach_id = ?", String.class, coachProfileId);
        assertThat(tier).isEqualTo(CoachSubscriptionTier.INSTRUCTOR.name());

        Long subscriptionCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM marketplace.coach_subscriptions WHERE coach_id = ?", Long.class, coachProfileId);
        assertThat(subscriptionCount).as("must not create a second row").isEqualTo(1L);
    }

    /**
     * skillars-deferred-131 AC1 Fix 3: {@code deleteStrike}'s tier-3 branch has the identical gap as
     * {@code reinstateCoach} — deleting the coach's one strike drops the fresh count below
     * visibilityThreshold, landing on the ACTIVE branch, which must now fail on an incomplete profile.
     */
    @Test
    void deleteStrike_incompleteProfile_keepsDeletionAndSkipsActivation() {
        UUID strikeToDelete = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM marketplace.coach_pricing WHERE coach_id = ?", coachProfileId);
            seedStrike(strikeToDelete);
            return null;
        });

        // Code review 2026-09-23 (Decision 1): a thrown MarketplaceException here used to roll back
        // this @Transactional method's whole transaction — silently undoing the strike deletion itself
        // along with the admin's request. deleteStrike now catches it: the strike stays deleted, the
        // coach's status is left untouched, and no exception propagates.
        enforcementService.deleteStrike(coachProfileId, strikeToDelete, "test", ADMIN_ID);

        Integer strikeCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM marketplace.coach_reliability_strikes WHERE id = ?",
            Integer.class, strikeToDelete);
        assertThat(strikeCount).as("the strike deletion must survive even though activation failed").isZero();

        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, coachProfileId);
        assertThat(status).as("a failed validation must not leave the coach ACTIVE").isEqualTo("PENDING_REVIEW");
    }

    /** skillars-deferred-131 AC1 Fix 3: same find-or-create proof as reinstateCoach, for deleteStrike. */
    @Test
    void deleteStrike_completeProfileNoSubscription_createsScoutTierRow() {
        UUID strikeToDelete = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            seedStrike(strikeToDelete);
            return null;
        });

        enforcementService.deleteStrike(coachProfileId, strikeToDelete, "test", ADMIN_ID);

        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, coachProfileId);
        assertThat(status).isEqualTo("ACTIVE");

        String tier = jdbcTemplate.queryForObject(
            "SELECT tier FROM marketplace.coach_subscriptions WHERE coach_id = ?", String.class, coachProfileId);
        assertThat(tier).isEqualTo(CoachSubscriptionTier.SCOUT.name());
    }

    /** skillars-deferred-131 AC1 Fix 3: same tier-preservation proof as reinstateCoach, for deleteStrike. */
    @Test
    void deleteStrike_existingSubscription_preservesExistingTier() {
        UUID strikeToDelete = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_subscriptions (coach_id, tier, active_since) VALUES (?, 'ACADEMY', now())",
                coachProfileId);
            seedStrike(strikeToDelete);
            return null;
        });

        enforcementService.deleteStrike(coachProfileId, strikeToDelete, "test", ADMIN_ID);

        String tier = jdbcTemplate.queryForObject(
            "SELECT tier FROM marketplace.coach_subscriptions WHERE coach_id = ?", String.class, coachProfileId);
        assertThat(tier).isEqualTo(CoachSubscriptionTier.ACADEMY.name());

        Long subscriptionCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM marketplace.coach_subscriptions WHERE coach_id = ?", Long.class, coachProfileId);
        assertThat(subscriptionCount).as("must not create a second row").isEqualTo(1L);
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
