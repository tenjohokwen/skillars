package com.softropic.skillars.platform.marketplace.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.admin.service.AdminCoachEnforcementService;
import com.softropic.skillars.platform.marketplace.contract.MarketplaceException;
import com.softropic.skillars.platform.marketplace.contract.ProfileBuilderStep1Request;
import com.softropic.skillars.platform.marketplace.contract.ProfileBuilderStep2Request;
import com.softropic.skillars.platform.marketplace.contract.ProfileBuilderStep3Request;
import com.softropic.skillars.platform.marketplace.contract.ProfileBuilderStep4Request;
import com.softropic.skillars.platform.marketplace.contract.ProfileBuilderStepResponse;
import com.softropic.skillars.platform.security.contract.AgeTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalTime;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * skillars-deferred-130 AC1 Fix 2 / Fix 3. {@code CoachProfileBuilderIT} drives the profile builder
 * over HTTP and has no seam to interleave two {@code publishProfile} calls or a concurrent
 * {@code suspendCoach}, so these races need a service-level test. Mirrors
 * {@code ReviewFlagServiceConcurrencyIT}'s / {@code RadarCompositeCalculationServiceConcurrencyIT}'s
 * own shape: autowire the services directly against real Testcontainers Postgres.
 */
class CoachProfileServiceConcurrencyIT extends AbstractIntegrationTest {

    @Autowired private CoachProfileService coachProfileService;
    @Autowired private AdminCoachEnforcementService adminCoachEnforcementService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private static final long COACH_USER_ID = 8070_000_001L;
    private static final long ADMIN_ID = 8070_000_099L;

    private UUID profileId;

    @BeforeEach
    void setUp() {
        transactionTemplate.execute(status -> {
            insertCoachUser(COACH_USER_ID, "coach.publishrace@skillars-test.com");
            return null;
        });

        // Complete all 4 builder steps directly against the service, leaving a DRAFT profile ready
        // to publish — mirrors CoachProfileBuilderIT's saveAllSteps(...) shape (minus step 5, which
        // publishProfile does not require).
        ProfileBuilderStepResponse step1 = coachProfileService.saveStep1(COACH_USER_ID,
            new ProfileBuilderStep1Request("Race Coach", "Bio", "Berlin", "Mitte",
                List.of("English"), "Europe/Berlin"));
        profileId = step1.coachId();
        coachProfileService.saveStep2(COACH_USER_ID,
            new ProfileBuilderStep2Request(List.of("Dribbling"), List.of(AgeTier.ADULT)));
        coachProfileService.saveStep3(COACH_USER_ID,
            new ProfileBuilderStep3Request(BigDecimal.valueOf(50.0), null, null));
        coachProfileService.saveStep4(COACH_USER_ID,
            new ProfileBuilderStep4Request(List.of(new ProfileBuilderStep4Request.AvailabilityWindowRequest(
                (short) 1, LocalTime.of(9, 0), LocalTime.of(11, 0), "Europe/Berlin"))));
    }

    /**
     * Two concurrent {@code publishProfile} calls for the same profile (a genuine accidental
     * double-click, or a client retry after a slow/timed-out first response). Whichever loses —
     * either at AC1 Fix 3's own lock-and-recheck guard (most likely, since it now runs before the
     * subscription insert) or, if that guard's window is somehow missed, at AC1 Fix 2's
     * constraint-name catch around the subscription insert — must receive the same
     * {@code marketplace.alreadyPublished}/422 the non-concurrent pre-check throws, not a generic
     * 400. Exactly one {@code ACTIVE} status and one {@code coach_subscriptions} row must result.
     */
    @Test
    void concurrentPublish_exactlyOneSucceeds_loserGetsAlreadyPublishedNotGeneric400() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);

            Callable<Throwable> task = () -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                try {
                    coachProfileService.publishProfile(COACH_USER_ID);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            };

            Future<Throwable> f1 = executor.submit(task);
            Future<Throwable> f2 = executor.submit(task);
            start.countDown();

            Throwable r1 = f1.get(30, TimeUnit.SECONDS);
            Throwable r2 = f2.get(30, TimeUnit.SECONDS);

            List<Throwable> results = java.util.Arrays.asList(r1, r2);
            long successes = results.stream().filter(java.util.Objects::isNull).count();
            long failures = results.stream().filter(java.util.Objects::nonNull).count();

            assertThat(successes).as("exactly one of the two concurrent publishProfile calls must succeed").isEqualTo(1);
            assertThat(failures).as("exactly one of the two concurrent publishProfile calls must fail").isEqualTo(1);

            Throwable failure = r1 != null ? r1 : r2;
            assertThat(failure).isInstanceOf(MarketplaceException.class);
            assertThat(((MarketplaceException) failure).getErrorCode())
                .as("the losing caller must get the same alreadyPublished code the non-concurrent "
                    + "pre-check throws, not a generic data-error code from an unmapped PK violation")
                .isEqualTo("marketplace.alreadyPublished");

            String finalStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, profileId);
            Integer subscriptionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM marketplace.coach_subscriptions WHERE coach_id = ?", Integer.class, profileId);
            assertThat(finalStatus).isEqualTo("ACTIVE");
            assertThat(subscriptionCount).as("exactly one subscription row, never two").isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * A {@code suspendCoach} committed by an admin inside a concurrent {@code publishProfile}'s
     * unlocked window must not be silently reverted back to {@code ACTIVE} — the exact failure mode
     * {@code suspendCoach}'s own comment names ("two writers serialise only when BOTH take it").
     * Holds {@code suspendCoach}'s own row lock open past its normal method boundary (by calling it
     * INSIDE an outer {@code TransactionTemplate.execute} and pausing before that lambda returns,
     * mirroring {@code ReviewFlagServiceConcurrencyIT}'s technique) so {@code publishProfile}'s own
     * lock-and-recheck block is forced to block on, then observe, the just-committed suspension.
     */
    @Test
    void concurrentSuspend_isNotRevertedByPublish() throws Exception {
        CountDownLatch suspendLockHeld = new CountDownLatch(1);
        CountDownLatch releaseSuspend = new CountDownLatch(1);
        AtomicReference<Throwable> suspendFailure = new AtomicReference<>();
        AtomicReference<Instant> suspendCommittedAt = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> suspender = executor.submit(() -> {
                try {
                    transactionTemplate.execute(status -> {
                        adminCoachEnforcementService.suspendCoach(profileId, "policy violation", false, ADMIN_ID);
                        suspendLockHeld.countDown();
                        try {
                            boolean released = releaseSuspend.await(30, TimeUnit.SECONDS);
                            if (!released) {
                                throw new AssertionError("releaseSuspend was never signalled within 30s");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return null;
                    });
                    suspendCommittedAt.set(Instant.now());
                } catch (Throwable t) {
                    suspendFailure.set(t);
                }
            });

            AtomicReference<Throwable> publishOutcome = new AtomicReference<>();
            AtomicReference<Instant> publishCompletedAt = new AtomicReference<>();
            Future<?> publisher = executor.submit(() -> {
                try {
                    boolean lockHeld = suspendLockHeld.await(10, TimeUnit.SECONDS);
                    if (!lockHeld) {
                        throw new AssertionError("suspendLockHeld was never signalled within 10s");
                    }
                    coachProfileService.publishProfile(COACH_USER_ID);
                } catch (Throwable t) {
                    publishOutcome.set(t);
                } finally {
                    publishCompletedAt.set(Instant.now());
                }
            });

            assertThat(suspendLockHeld.await(10, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(300);
            releaseSuspend.countDown();

            suspender.get(30, TimeUnit.SECONDS);
            publisher.get(30, TimeUnit.SECONDS);

            if (suspendFailure.get() != null) {
                throw new AssertionError("suspendCoach thread failed", suspendFailure.get());
            }

            assertThat(publishCompletedAt.get())
                .as("publishProfile's own UPDATE must not complete until suspendCoach's transaction "
                    + "actually commits and releases the row lock — this proves general serialization "
                    + "on the row, not specifically that publishProfile's own lock-acquisition code ran "
                    + "(an unlocked UPDATE would block on the same held lock too)")
                .isAfterOrEqualTo(suspendCommittedAt.get());

            assertThat(publishOutcome.get())
                .as("publishProfile must fail once it observes the committed suspension under its own "
                    + "lock, not silently overwrite it back to ACTIVE")
                .isInstanceOf(MarketplaceException.class);
            assertThat(((MarketplaceException) publishOutcome.get()).getErrorCode())
                .as("SUSPENDED is not ACTIVE, so this must be the generic not-eligible-to-publish code, "
                    + "not marketplace.alreadyPublished (which is factually false for a profile that "
                    + "was never published)")
                .isEqualTo("marketplace.profileNotEligibleToPublish");

            String finalStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, profileId);
            Integer subscriptionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM marketplace.coach_subscriptions WHERE coach_id = ?", Integer.class, profileId);
            assertThat(finalStatus).as("the suspension must survive, not be reverted to ACTIVE").isEqualTo("SUSPENDED");
            assertThat(subscriptionCount).as("no subscription row for a profile that never actually published")
                .isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    /** The existing non-concurrent already-published pre-check must remain unaffected by AC1 Fix 3's new lock. */
    @Test
    void sequentialDoublePublish_secondCallGetsAlreadyPublished() {
        coachProfileService.publishProfile(COACH_USER_ID);

        assertThatThrownBy(() -> coachProfileService.publishProfile(COACH_USER_ID))
            .isInstanceOf(MarketplaceException.class)
            .satisfies(e -> assertThat(((MarketplaceException) e).getErrorCode())
                .isEqualTo("marketplace.alreadyPublished"));
    }

    private void insertCoachUser(long id, String email) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', ?, ?, 'Test', 'OTHER', 'en', 'Coach', 'DE', ?, " +
            "true, false, ?, 'EMAIL', 'x', false, " +
            "'COACH', 'BASIC_VERIFIED')",
            id,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            Date.valueOf(LocalDate.of(1990, 3, 15)),
            email,
            "807" + (id % 10000000),
            email);
    }
}
