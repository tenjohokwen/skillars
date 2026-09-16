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
     * {@code findByStatusAndPastDueSinceBefore} carries no config-bound batch-size ceiling either
     * (known gap, out of scope here); per-row work in
     * {@link SubscriptionService#checkPastDueGracePeriod()} is a couple of DB round-trips with no
     * external HTTP call. {@code PT30M}/{@code PT2M} sized identically, for the same reasons.
     */
    @SchedulerLock(name = "SubscriptionGracePeriodChecker_checkGracePeriods",
                   lockAtMostFor = "PT30M", lockAtLeastFor = "PT2M")
    @Scheduled(cron = "0 0 3 * * *")
    public void checkGracePeriods() {
        log.info("[SUBSCRIPTION_GRACE_PERIOD_CHECKER] Running PAST_DUE grace period check");
        subscriptionService.checkPastDueGracePeriod();
    }
}
