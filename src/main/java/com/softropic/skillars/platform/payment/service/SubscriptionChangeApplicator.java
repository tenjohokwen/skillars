package com.softropic.skillars.platform.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionChangeApplicator {

    private final SubscriptionService subscriptionService;

    /**
     * skillars-deferred-116 AC3 sizing basis: unlike {@code VideoLifecycleScheduler}, neither
     * {@code findPendingForScheduler} query is bounded by a config-driven batch-size ceiling today
     * (known gap — not fixed here, would need a LIMIT + re-run-until-empty shape to add safely; out
     * of this story's scope per its own Dev Notes). Sized off worst-case arithmetic instead of a
     * production-DB row count, consistent with every other scheduler sizing in this codebase.
     * Per-row work in {@link SubscriptionService#applyPendingChanges()} is at most a couple of DB
     * round-trips (read + save the subscription, {@code syncMarketplaceTier}'s own read-then-write)
     * with no external HTTP call anywhere in either the coach or player loop — a materially cheaper
     * per-row cost than {@code VideoLifecycleScheduler}'s provider-HTTP-bound rows. At a pessimistic
     * 500ms/row (heavy DB contention, not the sub-50ms happy path) and an assumed worst-case combined
     * coach+player daily volume of 2000 rows — already an order of magnitude above any plausible
     * near-term scale for this platform, since there is no ceiling to size off instead —
     * {@code 2000 × 500ms ≈ 17 minutes}. {@code PT30M} sits comfortably above that with real margin.
     * {@code lockAtLeastFor = "PT2M"} is carried over from {@code SessionPackForfeitureScheduler} for
     * consistency, not because this once-daily cron independently needs a 2-minute floor — it is a
     * cheap defensive guard against a pathological fast-fail-and-immediately-refire edge case, not
     * load-bearing for this story's correctness.
     * <p>{@code @SchedulerLock} is the primary defense against a double-apply on overlap/multi-instance
     * concurrency; reapplying the same {@code toTier} being a no-op is a secondary safety net, not the
     * primary one — if the lock is ever removed or misconfigured, that idempotency is what stands
     * between a lock regression and an actual double-write.
     */
    @SchedulerLock(name = "SubscriptionChangeApplicator_applyPendingChanges",
                   lockAtMostFor = "PT30M", lockAtLeastFor = "PT2M")
    @Scheduled(cron = "0 0 2 * * *")
    public void applyPendingChanges() {
        log.info("[SUBSCRIPTION_CHANGE_APPLICATOR] Running scheduled downgrade application");
        subscriptionService.applyPendingChanges();
    }
}
