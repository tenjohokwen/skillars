package com.softropic.skillars.platform.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * skillars-deferred-135 AC2. Mirrors {@link SubscriptionTierReconciliationScheduler}'s own thin
 * wrapper/delegate split — see {@link SubscriptionService#reconcileStripeSubscriptions()}'s own
 * Javadoc for what gap this closes and why it exists as a separate scheduler from that sibling.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StripeSubscriptionReconciliationScheduler {

    private final SubscriptionService subscriptionService;

    /**
     * Sized from Stripe API round-trip latency, not local DB work — unlike its three siblings
     * ({@code SubscriptionChangeApplicator} 02:00, {@code SubscriptionGracePeriodChecker} 03:00,
     * {@code SubscriptionTierReconciliationScheduler} 04:00, all DB-only per-row costs), this sweep's
     * per-item cost is dominated by Stripe's own network latency for each paginated {@code
     * GET /v1/subscriptions} page (typically a few hundred ms, but budgeted pessimistically at 2s/page
     * to absorb real-world tail latency and any brief Stripe-side degradation) plus a handful of local
     * DB reads per subscription (cheap, sub-50ms). At Stripe's own 100-per-page maximum and an assumed
     * worst-case combined live-subscription volume of 2000 across all 3 statuses (ACTIVE/TRIALING/
     * PAST_DUE combined) — already an order of magnitude above any plausible near-term scale for this
     * platform, matching {@code SubscriptionChangeApplicator}'s own sizing precedent for the identical
     * reason (no production deploy has ever happened, {@code skillars-deferred-117}) — that is 20 pages
     * per status × 3 statuses = 60 pages × 2s ≈ 2 minutes for pagination alone, plus 2000 × ~50ms ≈ 100s
     * for the per-subscription local reads ≈ 3-4 minutes total. {@code PT15M} sits comfortably above
     * that with real margin. Scheduled at 05:00, after all three siblings (02:00, 03:00, 04:00) and
     * confirmed unclaimed by any other scheduler in this codebase at the time this was added.
     */
    @SchedulerLock(name = "StripeSubscriptionReconciliationScheduler_reconcileStripeSubscriptions",
                   lockAtMostFor = "PT15M", lockAtLeastFor = "PT2M")
    @Scheduled(cron = "0 0 5 * * *")
    public void reconcileStripeSubscriptions() {
        log.info("[STRIPE_SUBSCRIPTION_RECONCILIATION] Running scheduled Stripe->payment reconciliation sweep");
        subscriptionService.reconcileStripeSubscriptions();
    }
}
