package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

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

    private static final long PARENT_USER_ID = 9578000001L;
    private static final long PLAYER_ID = 9578000010L;
    private static final long LOCK_HOLD_MILLIS = 1200;

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
