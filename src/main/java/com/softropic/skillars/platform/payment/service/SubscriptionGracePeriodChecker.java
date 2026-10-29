package com.softropic.skillars.platform.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionGracePeriodChecker {

    private final SubscriptionService subscriptionService;

    /**
     * skillars-deferred-116 AC3 sizing basis — same arithmetic and reasoning as
     * {@link SubscriptionChangeApplicator#applyPendingChanges()}'s own sizing comment:
     * {@code findByStatusInAndPastDueSinceBefore} carries no config-bound batch-size ceiling either
     * (known gap, out of scope here). Per-row work in
     * {@link SubscriptionService#checkPastDueGracePeriod()} is a couple of DB round-trips plus, for an
     * expired row, one idempotent Stripe cancel (fix/payment-subscription-entitlements). Covers
     * PAST_DUE, UNPAID and PAUSED (fix/payment-subscription-payment-method-sync).
     */
    @SchedulerLock(name = "SubscriptionGracePeriodChecker_checkGracePeriods",
                   lockAtMostFor = "PT30M", lockAtLeastFor = "PT2M")
    @Scheduled(cron = "0 0 3 * * *")
    public void checkGracePeriods() {
        log.info("[SUBSCRIPTION_GRACE_PERIOD_CHECKER] Running PAST_DUE/UNPAID/PAUSED grace period check");
        subscriptionService.checkPastDueGracePeriod();
    }
}
