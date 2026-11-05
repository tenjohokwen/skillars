package com.softropic.skillars.platform.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * skillars-deferred-169 AC3: one ShedLock job with four independent duties, each in its own {@code try/catch} so one failing
 * cannot starve the others:
 * <ol>
 *   <li>resolve open session-pack purchase attempts older than the minimum age from Stripe (RS06);</li>
 *   <li>purge terminal attempts past retention (RS06);</li>
 *   <li>drain parked webhook events whose subscription is now linked (RS05) - the recovery path when the request-thread drain
 *       or the webhook's post-commit step failed; the job cadence is the recovery latency;</li>
 *   <li>purge parked events that never linked, past retention (RS05).</li>
 * </ol>
 * Not {@code @Transactional}: it calls transactional collaborators, so no connection is held across a Stripe call
 * ({@code SchedulerLockTransactionOrderingIT}). {@code lockAtMostFor} is 2x the fixed delay, the sibling jobs' margin.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentRecoveryJob {

    private final SessionPackPaymentService sessionPackPaymentService;
    private final SubscriptionService subscriptionService;

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    @SchedulerLock(name = "PaymentRecoveryJob_run", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void run() {
        try {
            int resolved = sessionPackPaymentService.resolveStaleAttempts();
            if (resolved > 0) log.info("PaymentRecoveryJob: resolved pack purchase attempts count={}", resolved);
        } catch (RuntimeException e) {
            log.error("[PAYMENT_RECOVERY_JOB_DUTY_FAILED] duty=resolveStaleAttempts", e);
        }
        try {
            sessionPackPaymentService.purgeTerminalAttempts();
        } catch (RuntimeException e) {
            log.error("[PAYMENT_RECOVERY_JOB_DUTY_FAILED] duty=purgeTerminalAttempts", e);
        }
        try {
            subscriptionService.drainLinkableParkedEvents();
        } catch (RuntimeException e) {
            log.error("[PAYMENT_RECOVERY_JOB_DUTY_FAILED] duty=drainLinkableParkedEvents", e);
        }
        try {
            subscriptionService.purgeStaleParkedEvents();
        } catch (RuntimeException e) {
            log.error("[PAYMENT_RECOVERY_JOB_DUTY_FAILED] duty=purgeStaleParkedEvents", e);
        }
    }
}
