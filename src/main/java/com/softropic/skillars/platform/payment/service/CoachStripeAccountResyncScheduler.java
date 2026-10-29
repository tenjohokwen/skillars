package com.softropic.skillars.platform.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * fix/prod-readiness-gaps: daily safety net for a missed {@code account.updated} on a coach whose Stripe
 * account is already {@code COMPLETE} — see {@link StripeOnboardingService#resyncCompleteAccounts}.
 *
 * <p>Daily at 07:00 server time, one instance at a time (ShedLock). One sequential {@code Account.retrieve}
 * per account, budgeted at 1s each: {@link #MAX_PAGES} x {@link #PAGE_SIZE} = 5,000 accounts fits well
 * inside {@code PT2H}. Stripe's live read limit (100/s) is never approached.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoachStripeAccountResyncScheduler {

    static final int PAGE_SIZE = 200;
    /** At most {@code MAX_PAGES * PAGE_SIZE} accounts per run. */
    static final int MAX_PAGES = 25;

    private final StripeOnboardingService stripeOnboardingService;

    @Scheduled(cron = "0 0 7 * * *")
    @SchedulerLock(name = "CoachStripeAccountResyncScheduler_resyncCompleteAccounts",
                   lockAtMostFor = "PT2H", lockAtLeastFor = "PT2M")
    public void resyncCompleteAccounts() {
        stripeOnboardingService.resyncCompleteAccounts(PAGE_SIZE, MAX_PAGES);
    }
}
