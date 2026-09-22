package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.config.service.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Deferred-77 AC10 Phase 1: recalculateComposite must contend for the same player_profiles row lock
 * as any other concurrent holder, mirroring BookingServiceConcurrencyIT's raw-SELECT-FOR-UPDATE
 * locker-thread shape rather than trying to race two real recalculations against each other (whose
 * completion order is not independently observable without invasive instrumentation).
 */
class RadarCompositeCalculationServiceConcurrencyIT extends AbstractIntegrationTest {

    @Autowired private RadarCompositeCalculationService compositeCalculationService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private ConfigService configService;

    private static final long PARENT_USER_ID = 9578000001L;
    private static final long PLAYER_ID = 9578000010L;
    private static final long LOCK_HOLD_MILLIS = 1200;
    private static final String SKILL = "PAC";

    @BeforeEach
    void setUp() {
        transactionTemplate.execute(status -> {
            insertParentUser();
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Radar Concurrency Player', ?, 'MIDFIELDER', 'AGE_10_12', ?, false, ?, 'system')",
                PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(10)),
                PARENT_USER_ID, Timestamp.from(Instant.now()));
            return null;
        });
    }

    @Test
    void recalculateComposite_playerRowLockedByAnotherSession_blocksUntilReleasedThenSucceeds() throws Exception {
        CountDownLatch lockHeld = new CountDownLatch(1);
        AtomicReference<Throwable> lockerFailure = new AtomicReference<>();
        AtomicReference<Instant> lockReleasedAt = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<?> locker = executor.submit(() -> {
            try {
                transactionTemplate.execute(status -> {
                    jdbcTemplate.queryForObject(
                        "SELECT id FROM main.player_profiles WHERE id = ? FOR UPDATE",
                        Long.class, PLAYER_ID);
                    lockHeld.countDown();
                    try {
                        Thread.sleep(LOCK_HOLD_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("Interrupted while holding the player_profiles lock", e);
                    }
                    return null;
                });
                lockReleasedAt.set(Instant.now());
            } catch (Throwable t) {
                lockerFailure.set(t);
            }
        });

        AtomicReference<Instant> recalculateCompletedAt = new AtomicReference<>();
        AtomicReference<Throwable> recalculateFailure = new AtomicReference<>();
        Future<?> recalculator = executor.submit(() -> {
            try {
                lockHeld.await(10, TimeUnit.SECONDS);
                compositeCalculationService.recalculateComposite(PLAYER_ID, PARENT_USER_ID, Set.of("PAC"));
                recalculateCompletedAt.set(Instant.now());
            } catch (Throwable t) {
                recalculateFailure.set(t);
            }
        });

        locker.get(30, TimeUnit.SECONDS);
        recalculator.get(30, TimeUnit.SECONDS);
        executor.shutdown();

        if (lockerFailure.get() != null) {
            throw new AssertionError("Locking thread failed", lockerFailure.get());
        }
        if (recalculateFailure.get() != null) {
            throw new AssertionError("recalculateComposite failed", recalculateFailure.get());
        }

        assertThat(recalculateCompletedAt.get())
            .as("recalculateComposite must not complete until the raw SELECT ... FOR UPDATE lock is "
                + "released — proving it contends for the same player_profiles row lock")
            .isAfterOrEqualTo(lockReleasedAt.get());
    }

    /**
     * skillars-deferred-127 code review (2026-09-21): the shared {@code player_profiles} lock alone
     * does not close the resurrection race through {@code radar_assessment_entries} — {@code
     * RadarAssessmentService.submitAssessment} writes that table without taking this lock at all. The
     * fix is a sticky {@code development_data_erased_at} tombstone, set by {@code
     * GdprErasureService.deletePlayerDevelopmentData} and checked by {@code recalculateComposite}
     * immediately after it re-acquires/refreshes the lock. This test proves the checking half of that
     * mechanism directly: with the tombstone already set (simulating "erasure already committed
     * before this call ever started" — the exact state a real interleaving would leave), a
     * {@code recalculateComposite} call for that player must skip entirely and create nothing, even
     * though a genuine assessment row exists that would otherwise make the per-skill loop non-empty.
     */
    @Test
    void recalculateComposite_playerAlreadyTombstoned_skipsWithoutUpsertingAnything() {
        seedRadarAssessment();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "UPDATE main.player_profiles SET development_data_erased_at = now() WHERE id = ?",
                PLAYER_ID);
            return null;
        });

        compositeCalculationService.recalculateComposite(PLAYER_ID, PARENT_USER_ID, Set.of(SKILL));

        int compositeCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.player_radar_composites WHERE player_id = ?",
            Integer.class, PLAYER_ID);
        int baselineCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.player_radar_baselines WHERE player_id = ?",
            Integer.class, PLAYER_ID);
        assertThat(compositeCount).as("tombstoned player must get no composite row").isZero();
        assertThat(baselineCount).as("tombstoned player must get no baseline row").isZero();
    }

    /**
     * skillars-deferred-126 AC2 Task 5. Reuses this exact class (per this AC's own Dev Notes — not a
     * from-scratch design, not {@code AdminCoachEnforcementIsolationRuntimeIT}), extended with the
     * three preconditions the existing fixture above does NOT satisfy: (a) a seeded {@code
     * radar_assessments} row so the per-skill loop actually runs {@code upsertComposite}/{@code
     * insertBaselineIfAbsent} at all (with zero rows, {@code bySkill} is empty and neither upsert call
     * ever fires); (b) a pre-seeded, COMMITTED {@code development.player_radar_composites} row for the
     * exact {@code (player_id, skill_code)} key — {@code upsertComposite}'s {@code DO UPDATE} conflicts
     * with (and locks) an existing committed row, unlike {@code insertBaselineIfAbsent}'s {@code
     * DO NOTHING}, which does not contend with an already-committed row at all; (c) the competing lock
     * held on that seeded composites row itself, not the {@code player_profiles} row the sibling test
     * above targets (that lock is the already-bounded {@code findByIdForUpdate} NOWAIT path, unrelated
     * to what this AC bounds).
     */
    @Test
    void recalculateComposite_compositeRowLockedByConcurrentUncommittedTransaction_failsWithinBoundedTime() throws Exception {
        seedRadarAssessment();
        seedCommittedCompositeRow();
        setLockTimeoutSecondsConfig(2);

        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        AtomicReference<Throwable> lockerFailure = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<?> locker = executor.submit(() -> {
            try {
                transactionTemplate.execute(status -> {
                    jdbcTemplate.queryForObject(
                        "SELECT player_id FROM development.player_radar_composites "
                            + "WHERE player_id = ? AND skill_code = ? FOR UPDATE",
                        Long.class, PLAYER_ID, SKILL);
                    lockHeld.countDown();
                    try {
                        // Bounded wait, not indefinite: if the assertion below fails and never
                        // releases us, this still lets the JVM exit rather than hanging the suite.
                        releaseLock.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            } catch (Throwable t) {
                lockerFailure.set(t);
            }
        });

        try {
            assertThat(lockHeld.await(10, TimeUnit.SECONDS))
                .as("competing lock on the player_radar_composites row must be acquired before timing "
                    + "recalculateComposite's own attempt to write it")
                .isTrue();

            Instant before = Instant.now();
            Throwable thrown = catchThrowable(() ->
                compositeCalculationService.recalculateComposite(PLAYER_ID, PARENT_USER_ID, Set.of(SKILL)));
            Duration elapsed = Duration.between(before, Instant.now());

            if (lockerFailure.get() != null) {
                throw new AssertionError("Locking thread failed", lockerFailure.get());
            }
            // skillars-deferred-126 AC2 Task 4: exact exception class empirically confirmed (not
            // assumed) — a plain SET-config lock_timeout expiry (55P03) surfaces as Spring's
            // PessimisticLockingFailureException, cause org.hibernate.PessimisticLockException. See
            // RadarCompositeCalculationService.recalculateComposite's own Javadoc for the full finding,
            // including the deadlock (40P01) case below, which translates to the SAME top-level Spring
            // exception class.
            assertThat(thrown)
                .as("a lock-timeout-bounded upsert wait must fail, not hang, once the configured "
                    + "lock_timeout elapses")
                .isNotNull()
                .isInstanceOf(PessimisticLockingFailureException.class)
                // /bmad-code-review fix (2026-09-21): the Javadoc above claims this cause was
                // "empirically confirmed", but nothing here pinned it — a regression to some other
                // cause (still wrapped in the same top-level PessimisticLockingFailureException, so
                // the assertion above alone would not catch it) would have passed silently.
                .hasCauseInstanceOf(org.hibernate.PessimisticLockException.class);
            assertThat(elapsed)
                .as("must fail within a bounded wall-clock time (configured 2s lock_timeout + real "
                    + "margin for CI scheduling jitter), not hang past it — AND must not fail near-"
                    + "instantly, which would mean the failure came from some other, unrelated NOWAIT "
                    + "lock (e.g. player_profiles) rather than the set_config-bounded upsert wait this "
                    + "test exists to exercise")
                .isGreaterThanOrEqualTo(Duration.ofSeconds(2))
                .isLessThan(Duration.ofSeconds(15));
        } finally {
            releaseLock.countDown();
            locker.get(10, TimeUnit.SECONDS);
            executor.shutdown();
        }
    }

    /**
     * skillars-deferred-126 AC2 Task 4: empirically confirms the exception Spring translates a genuine
     * lock-ordering deadlock (Postgres {@code 40P01}) to — the SAME top-level {@code
     * PessimisticLockingFailureException} the plain lock-timeout wait test above exercises,
     * distinguishable from it only by cause. Reproduces the exact opposite-order table collision
     * between {@code recalculateComposite} (composites then baselines) and {@code
     * GdprErasureService.deletePlayerDevelopmentData} (baselines then composites) via direct
     * repository/JDBC calls under full latch control — {@code recalculateComposite} itself has no
     * injection point between its two internal upsert calls to synchronize on from outside, so this
     * drives both sides of the collision directly rather than through the production method.
     * <strong>Historical note (skillars-deferred-127 AC1, 2026-09-21):</strong> production
     * {@code deletePlayerDevelopmentData} now takes a shared {@code player_profiles} pessimistic lock
     * upstream of either table, which fully serializes it against {@code recalculateComposite} and so
     * this exact collision can no longer occur end-to-end in production. This test still drives both
     * table orders directly with raw SQL/JDBC, independent of that production-code change, and remains
     * a valid, useful proof that the opposite-order collision — if it were ever reachable again —
     * still translates to the documented exception/cause pair; it is not itself testing the (now
     * closed) production reachability of that collision. Thread A's
     * own write below mirrors {@code PlayerRadarCompositeRepository.upsertComposite}'s exact {@code
     * INSERT ... ON CONFLICT DO UPDATE} statement shape (not a plain {@code UPDATE}
     * — /bmad-code-review fix, 2026-09-21: the previous version used a plain {@code UPDATE} while
     * claiming "the identical lock type", which was false), so this test exercises the same lock type
     * production code actually takes, not merely the same table order.
     */
    @Test
    void deadlockBetweenCompositeUpsertAndGdprStyleDelete_translatesToSameTopLevelExceptionClassButDifferentCause() throws Exception {
        seedCommittedCompositeRow();
        seedCommittedBaselineRow();

        CountDownLatch compositesLockedByA = new CountDownLatch(1);
        CountDownLatch baselinesLockedByB = new CountDownLatch(1);
        AtomicReference<Throwable> outcomeA = new AtomicReference<>();
        AtomicReference<Throwable> outcomeB = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Thread A: mirrors recalculateComposite's own order — composites, then (after B has locked
        // baselines) baselines.
        Future<?> threadA = executor.submit(() -> {
            try {
                transactionTemplate.execute(status -> {
                    // Mirrors PlayerRadarCompositeRepository.upsertComposite's exact statement shape
                    // (INSERT ... ON CONFLICT DO UPDATE), not a plain UPDATE — see this test's own
                    // Javadoc for why that distinction matters.
                    jdbcTemplate.update(
                        "INSERT INTO development.player_radar_composites "
                            + "(player_id, skill_code, composite_score, entry_count, distinct_coach_count, last_updated_at) "
                            + "VALUES (?, ?, 55.0, 1, 1, now()) "
                            + "ON CONFLICT (player_id, skill_code) DO UPDATE SET "
                            + "composite_score = EXCLUDED.composite_score, entry_count = EXCLUDED.entry_count, "
                            + "distinct_coach_count = EXCLUDED.distinct_coach_count, "
                            + "last_updated_at = EXCLUDED.last_updated_at",
                        PLAYER_ID, SKILL);
                    compositesLockedByA.countDown();
                    await(baselinesLockedByB);
                    jdbcTemplate.update(
                        "DELETE FROM development.player_radar_baselines WHERE player_id = ? AND skill_code = ?",
                        PLAYER_ID, SKILL);
                    return null;
                });
            } catch (Throwable t) {
                outcomeA.set(t);
            }
        });

        // Thread B: mirrors GdprErasureService.deletePlayerDevelopmentData's own order — baselines,
        // then composites. (Historical framing, skillars-deferred-127 AC1: this order collision is
        // no longer reachable end-to-end in production now that deletePlayerDevelopmentData takes a
        // shared player_profiles lock upstream — see this test's class-level Javadoc above.)
        Future<?> threadB = executor.submit(() -> {
            try {
                await(compositesLockedByA);
                transactionTemplate.execute(status -> {
                    jdbcTemplate.update(
                        "DELETE FROM development.player_radar_baselines WHERE player_id = ? AND skill_code = ?",
                        PLAYER_ID, SKILL);
                    baselinesLockedByB.countDown();
                    jdbcTemplate.update(
                        "DELETE FROM development.player_radar_composites WHERE player_id = ? AND skill_code = ?",
                        PLAYER_ID, SKILL);
                    return null;
                });
            } catch (Throwable t) {
                outcomeB.set(t);
            }
        });

        threadA.get(20, TimeUnit.SECONDS);
        threadB.get(20, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly one side must be the deadlock's victim; the other completes normally (Postgres's
        // deadlock detector aborts exactly one of the two participating transactions).
        long failures = java.util.stream.Stream.of(outcomeA.get(), outcomeB.get()).filter(java.util.Objects::nonNull).count();
        assertThat(failures)
            .as("exactly one side of the circular wait must be aborted as the deadlock's victim")
            .isEqualTo(1);
        Throwable victim = outcomeA.get() != null ? outcomeA.get() : outcomeB.get();
        // skillars-deferred-126 AC2 Task 4: exact exception class empirically confirmed — a genuine
        // deadlock (40P01, "ERROR: deadlock detected") also translates to Spring's
        // PessimisticLockingFailureException (cause org.postgresql.util.PSQLException), the SAME
        // top-level exception class the plain lock-timeout-wait test above observes. The two failure
        // modes are distinguishable only by inspecting the cause, not by catching different types.
        // /bmad-code-review fix (2026-09-21): the cause itself is now pinned too, not just the
        // top-level class — without this, a regression to some other cause wrapped in the same
        // top-level exception would have passed silently, and this test's whole point is to prove the
        // two failure modes share a top-level class but differ in cause.
        assertThat(victim)
            .isInstanceOf(PessimisticLockingFailureException.class)
            .hasCauseInstanceOf(org.postgresql.util.PSQLException.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for latch " + latch);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for latch " + latch, e);
        }
    }

    private void seedRadarAssessment() {
        UUID coachId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO development.radar_assessment_entries " +
                "(id, assessment_group_id, coach_id, player_id, skill_code, score, assessment_date, assessment_type, created_at) " +
                "VALUES (gen_random_uuid(), gen_random_uuid(), ?, ?, ?, ?, CURRENT_DATE, 'OBJECTIVE'::development.assessment_type, NOW())",
                coachId, PLAYER_ID, SKILL, (short) 70);
            return null;
        });
    }

    private void seedCommittedCompositeRow() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO development.player_radar_composites " +
                "(player_id, skill_code, composite_score, entry_count, distinct_coach_count, last_updated_at) " +
                "VALUES (?, ?, 50.0, 1, 1, now()) ON CONFLICT (player_id, skill_code) DO NOTHING",
                PLAYER_ID, SKILL);
            return null;
        });
    }

    private void seedCommittedBaselineRow() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO development.player_radar_baselines " +
                "(player_id, skill_code, baseline_score, recorded_at) " +
                "VALUES (?, ?, 50.0, now()) ON CONFLICT (player_id, skill_code) DO NOTHING",
                PLAYER_ID, SKILL);
            return null;
        });
    }

    private void setLockTimeoutSecondsConfig(int seconds) {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.platform_config (key, value) " +
                "VALUES ('platform.radar_composite_lock_timeout_seconds', ?) " +
                "ON CONFLICT (key) DO UPDATE SET value = ?",
                String.valueOf(seconds), String.valueOf(seconds));
            return null;
        });
        configService.invalidate();
    }

    private void insertParentUser() {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', '1990-01-01', ?, 'Test', 'OTHER', 'en', 'User', 'DE', ?, " +
            "true, false, ?, 'EMAIL', ?, false, " +
            "'PARENT', 'BASIC_VERIFIED')",
            PARENT_USER_ID,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            "radarconc.parent@skillars-test.com",
            "69" + (PARENT_USER_ID % 100000000L),
            "radarconc.parent@skillars-test.com", "x"
        );
    }
}
