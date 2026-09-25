package com.softropic.skillars.platform.admin.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * skillars-deferred-136 AC2. Mirrors {@code StripeSubscriptionReconciliationScheduler}'s own thin
 * wrapper/delegate split — see {@link GdprErasureService#retryFailedErasures()}'s own Javadoc for the
 * gap this closes.
 *
 * <p>Cadence: GDPR erasure failures are expected to be rare (contested locks, transient connection-
 * pool pressure, or a genuine bug needing a human) — a daily sweep is almost certainly sufficient and
 * keeps this cheap. {@code lockAtMostFor} is sized generously above a pessimistic worst case: 50 rows
 * per page (this class's own {@code RETRY_SWEEP_PAGE_SIZE}) at up to ~15s each (the per-child deadline
 * budget plus overhead, {@code gdprEraseLockBudget}'s own ~10s default plus margin) is ~12.5 minutes
 * for one page; {@code PT30M} leaves real headroom even for a multi-page sweep, which would only
 * happen if the FAILED backlog were far larger than this system has ever seen. Scheduled at 06:00,
 * after {@code StripeSubscriptionReconciliationScheduler} (05:00) and before the 08:00 slot already
 * claimed elsewhere, confirmed unclaimed by any other scheduler in this codebase at the time this was
 * added.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GdprErasureRetryScheduler {

    private final GdprErasureService gdprErasureService;

    @SchedulerLock(name = "GdprErasureRetryScheduler_retryFailedErasures",
                   lockAtMostFor = "PT30M", lockAtLeastFor = "PT2M")
    @Scheduled(cron = "0 0 6 * * *")
    public void retryFailedErasures() {
        log.info("[GDPR_ERASURE_RETRY_SWEEP] Running scheduled GDPR erasure retry sweep");
        gdprErasureService.retryFailedErasures();
    }
}
