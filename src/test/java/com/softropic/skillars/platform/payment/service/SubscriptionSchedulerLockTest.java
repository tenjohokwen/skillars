package com.softropic.skillars.platform.payment.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-116 AC3: neither {@code SubscriptionChangeApplicator.applyPendingChanges()} nor
 * {@code SubscriptionGracePeriodChecker.checkGracePeriods()} carried {@code @SchedulerLock} before
 * this story, unlike every other stateful {@code @Scheduled} job in this module. Mirrors
 * {@code VideoLifecycleSchedulerTest}'s reflection/annotation-check pattern (the established
 * precedent for this exact kind of "no live scheduler run needed" assertion in this codebase).
 */
class SubscriptionSchedulerLockTest {

    @Test
    void applyPendingChanges_carriesSchedulerLock() throws NoSuchMethodException {
        Method method = SubscriptionChangeApplicator.class.getMethod("applyPendingChanges");
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        assertThat(lock).as("applyPendingChanges() must carry @SchedulerLock — without it, a "
            + "concurrent run (overlap or a second instance) can re-select and double-apply the same "
            + "not-yet-applied rows in the gap between the batch-load commit and the per-item writes")
            .isNotNull();
        assertThat(lock.name()).isNotBlank();
        assertThat(Duration.parse(lock.lockAtMostFor())).isPositive();
        assertThat(Duration.parse(lock.lockAtLeastFor())).isPositive();
    }

    @Test
    void checkGracePeriods_carriesSchedulerLock() throws NoSuchMethodException {
        Method method = SubscriptionGracePeriodChecker.class.getMethod("checkGracePeriods");
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        assertThat(lock).as("checkGracePeriods() must carry @SchedulerLock, for the same reason as "
            + "applyPendingChanges() — landed in the same change per AC3, not as a follow-up")
            .isNotNull();
        assertThat(lock.name()).isNotBlank();
        assertThat(Duration.parse(lock.lockAtMostFor())).isPositive();
        assertThat(Duration.parse(lock.lockAtLeastFor())).isPositive();
    }

    /**
     * skillars-deferred-132 AC1 Fix 3: a third scheduler, same reasoning as the two above — without
     * {@code @SchedulerLock}, a concurrent run (overlap or a second instance) could double-process the
     * same active/trialling coach subscriptions.
     */
    @Test
    void reconcileMarketplaceTiers_carriesSchedulerLock() throws NoSuchMethodException {
        Method method = SubscriptionTierReconciliationScheduler.class.getMethod("reconcileMarketplaceTiers");
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        assertThat(lock).as("reconcileMarketplaceTiers() must carry @SchedulerLock, mirroring its two "
            + "siblings")
            .isNotNull();
        assertThat(lock.name()).isNotBlank();
        assertThat(Duration.parse(lock.lockAtMostFor())).isPositive();
        assertThat(Duration.parse(lock.lockAtLeastFor())).isPositive();
    }
}
