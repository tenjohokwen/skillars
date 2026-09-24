package com.softropic.skillars.platform.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * skillars-deferred-132 AC1 Fix 3. Mirrors {@link SubscriptionChangeApplicator} and
 * {@link SubscriptionGracePeriodChecker}'s own {@code @SchedulerLock}/cadence conventions exactly —
 * see {@link SubscriptionService#reconcileMarketplaceTiers()}'s own javadoc for what gap this closes.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionTierReconciliationScheduler {

    private final SubscriptionService subscriptionService;

    /**
     * Sized identically to its two siblings' own {@code PT30M}/{@code PT2M} arithmetic (see
     * {@link SubscriptionChangeApplicator#applyPendingChanges()}'s own sizing comment) — this sweep's
     * per-row work ({@link SubscriptionService#reconcileMarketplaceTiers()}'s read-then-maybe-
     * {@code syncMarketplaceTier} write) is the same shape and cost as the two siblings' own per-row
     * work, over the same order-of-magnitude worst-case coach volume. Scheduled at 04:00, after both
     * siblings (02:00, 03:00), so a coach whose tier those two schedulers already touch tonight is not
     * redundantly reconciled again seconds later.
     */
    @SchedulerLock(name = "SubscriptionTierReconciliationScheduler_reconcileMarketplaceTiers",
                   lockAtMostFor = "PT30M", lockAtLeastFor = "PT2M")
    @Scheduled(cron = "0 0 4 * * *")
    public void reconcileMarketplaceTiers() {
        log.info("[SUBSCRIPTION_TIER_RECONCILIATION] Running scheduled marketplace tier reconciliation");
        subscriptionService.reconcileMarketplaceTiers();
    }
}
