package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.marketplace.contract.ProfileBuilderStep1Request;
import com.softropic.skillars.platform.marketplace.contract.ProfileBuilderStep2Request;
import com.softropic.skillars.platform.marketplace.contract.ProfileBuilderStep3Request;
import com.softropic.skillars.platform.marketplace.contract.ProfileBuilderStep4Request;
import com.softropic.skillars.platform.marketplace.contract.ProfileBuilderStepResponse;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.payment.repo.PaymentCoachSubscription;
import com.softropic.skillars.platform.security.contract.AgeTier;
import com.softropic.skillars.utils.ConcurrencyLockWaitSupport;
import com.stripe.model.Subscription;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-131 AC1 Fix 4. {@code SubscriptionService.syncMarketplaceTier}'s INSERT branch
 * needed a {@code FOR KEY SHARE} lock on {@code coach_profiles} at flush time
 * ({@code coach_subscriptions_coach_id_fkey}), which blocked behind
 * {@code CoachProfileService.publishProfile}'s own {@code FOR UPDATE} lock on that same row
 * (skillars-deferred-130) if the two ran concurrently for the same coach, then violated
 * {@code coach_subscriptions_pkey} once {@code publishProfile} committed first — uncaught, rolling
 * back {@code persistCoachSubscription}'s whole transaction. Taking the same row lock closes the race:
 * both writers now serialize on {@code coach_profiles} instead of racing on {@code coach_subscriptions}.
 *
 * <p>The final tier is deterministic regardless of which writer wins the lock, by code-reading
 * argument: {@code publishProfile}'s own find-or-create never overwrites an existing row's tier (only
 * defaults a genuinely new row to {@code SCOUT}), while {@code syncMarketplaceTier}'s
 * {@code ifPresentOrElse} always sets the tier on the present branch — so whichever of the two runs
 * second, the subscribed tier from {@code persistCoachSubscription} is what survives.
 *
 * <p>Code review 2026-09-23: the test below only exercises the {@code publishProfile}-holds-the-lock-
 * first ordering (the {@code publishLockHeld} latch forces {@code persistCoachSubscription} to always
 * be the contender, never the holder) — the reverse ordering is not separately exercised here. The
 * claim above about the reverse ordering rests on the code-reading argument, not on this test.
 */
class SubscriptionServiceConcurrencyIT extends AbstractIntegrationTest {

    @Autowired private CoachProfileService coachProfileService;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private MeterRegistry meterRegistry;

    private static final long COACH_USER_ID = 8100_000_001L;
    private static final String SUBSCRIBED_TIER = "INSTRUCTOR";

    private UUID coachProfileId;

    @BeforeEach
    void setUp() {
        transactionTemplate.execute(status -> {
            insertCoachUser(COACH_USER_ID, "coach.synctierrace@skillars-test.com");
            return null;
        });

        ProfileBuilderStepResponse step1 = coachProfileService.saveStep1(COACH_USER_ID,
            new ProfileBuilderStep1Request("Sync Tier Coach", "Bio", "Berlin", "Mitte",
                List.of("English"), "Europe/Berlin"));
        coachProfileId = step1.coachId();
        coachProfileService.saveStep2(COACH_USER_ID,
            new ProfileBuilderStep2Request(List.of("Dribbling"), List.of(AgeTier.ADULT)));
        coachProfileService.saveStep3(COACH_USER_ID,
            new ProfileBuilderStep3Request(BigDecimal.valueOf(50.0), null, null));
        coachProfileService.saveStep4(COACH_USER_ID,
            new ProfileBuilderStep4Request(List.of(new ProfileBuilderStep4Request.AvailabilityWindowRequest(
                (short) 1, LocalTime.of(9, 0), LocalTime.of(11, 0), "Europe/Berlin"))));
    }

    /**
     * skillars-deferred-131 AC3: holds {@code publishProfile}'s own transaction open past its normal
     * method boundary (calling it inside an outer {@code transactionTemplate.execute} and pausing
     * before that lambda returns, mirroring {@code CoachProfileServiceConcurrencyIT}'s own
     * {@code concurrentSuspend_isNotRevertedByPublish} technique) so {@code persistCoachSubscription}'s
     * own lock acquisition is forced to retry against it — confirmed via
     * {@code ConcurrencyLockWaitSupport.awaitLockRetry} (a {@code pg_locks} poll would spin until
     * timeout here, since {@code CoachProfileRepository.findByIdForUpdate} is NOWAIT, not blocking)
     * before the lock is released, rather than a fixed sleep that proves nothing about whether genuine
     * contention was ever exercised.
     */
    @Test
    void concurrentPublishAndSyncMarketplaceTier_exactlyOneRowSurvivesNoUncaughtViolation() throws Exception {
        Subscription stripeSub = mock(Subscription.class);
        when(stripeSub.getId()).thenReturn("sub_test_synctierrace");
        when(stripeSub.getStatus()).thenReturn("active");
        when(stripeSub.getCurrentPeriodEnd()).thenReturn(Instant.now().plusSeconds(2592000).getEpochSecond());

        PaymentCoachSubscription paymentSub = new PaymentCoachSubscription();
        paymentSub.setCoachId(coachProfileId);

        double lockRetryBaseline = ConcurrencyLockWaitSupport.currentLockRetryCount(meterRegistry);

        CountDownLatch publishLockHeld = new CountDownLatch(1);
        CountDownLatch releasePublish = new CountDownLatch(1);
        AtomicReference<Throwable> publishFailure = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> publisher = executor.submit(() -> {
                try {
                    transactionTemplate.execute(status -> {
                        coachProfileService.publishProfile(COACH_USER_ID);
                        publishLockHeld.countDown();
                        try {
                            boolean released = releasePublish.await(30, TimeUnit.SECONDS);
                            if (!released) {
                                throw new AssertionError("releasePublish was never signalled within 30s");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return null;
                    });
                } catch (Throwable t) {
                    publishFailure.set(t);
                }
            });

            AtomicReference<Throwable> syncOutcome = new AtomicReference<>();
            Future<?> syncer = executor.submit(() -> {
                try {
                    boolean lockHeld = publishLockHeld.await(10, TimeUnit.SECONDS);
                    if (!lockHeld) {
                        throw new AssertionError("publishLockHeld was never signalled within 10s");
                    }
                    subscriptionService.persistCoachSubscription(
                        coachProfileId, paymentSub, SUBSCRIBED_TIER, stripeSub);
                } catch (Throwable t) {
                    syncOutcome.set(t);
                }
            });

            assertThat(publishLockHeld.await(10, TimeUnit.SECONDS)).isTrue();
            // skillars-deferred-131 AC3: see ConcurrencyLockWaitSupport's own javadoc — a NOWAIT
            // contender's retry counter cannot gate a pre-release wait, so this bounded delay gives
            // the syncer's own NOWAIT failure and first backoff a real chance to happen; the genuine
            // retry is then confirmed post-hoc below.
            ConcurrencyLockWaitSupport.awaitFirstLockAttempt();
            releasePublish.countDown();

            publisher.get(30, TimeUnit.SECONDS);
            syncer.get(30, TimeUnit.SECONDS);

            if (publishFailure.get() != null) {
                throw new AssertionError("publishProfile thread failed", publishFailure.get());
            }
            assertThat(syncOutcome.get())
                .as("persistCoachSubscription must not throw an uncaught DataIntegrityViolationException")
                .isNull();

            ConcurrencyLockWaitSupport.assertGenuineLockRetryOccurred(meterRegistry, lockRetryBaseline);

            Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM marketplace.coach_subscriptions WHERE coach_id = ?",
                Integer.class, coachProfileId);
            assertThat(rowCount).as("exactly one coach_subscriptions row, never two").isEqualTo(1);

            String tier = jdbcTemplate.queryForObject(
                "SELECT tier FROM marketplace.coach_subscriptions WHERE coach_id = ?",
                String.class, coachProfileId);
            assertThat(tier)
                .as("the subscribed tier survives regardless of which writer wins the lock — "
                    + "publishProfile never overwrites an existing row's tier")
                .isEqualTo(SUBSCRIBED_TIER);

            String status = jdbcTemplate.queryForObject(
                "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, coachProfileId);
            assertThat(status).isEqualTo("ACTIVE");
        } finally {
            executor.shutdownNow();
        }
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
            "810" + (id % 10000000),
            email);
    }
}
