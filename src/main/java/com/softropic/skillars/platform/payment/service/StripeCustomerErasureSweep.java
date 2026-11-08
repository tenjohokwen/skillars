package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * skillars-deferred-177 (RS03i): the safety net for a Stripe customer whose erasure was never enqueued. A GDPR erasure
 * schedules the Stripe-side erasure for the {@code stripe_customers} row it finds; a first customer created while that
 * erasure was in flight gets it from {@link StripeCustomerEmailSyncOutboxSupport#syncIfEmailChangedSince}, which only logs a
 * failure. This job finds every payer whose account carries the erasure tombstone and whose row has no
 * {@code erasure_scheduled_at}, and calls {@link StripeCustomerErasureOutboxSupport#scheduleErasure} for each, one
 * transaction per payer, so one failure does not stop the others and is retried on the next run.
 *
 * <p>Hourly at minute 17, one instance at a time (ShedLock), at most {@link #BATCH_SIZE} payers per run.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StripeCustomerErasureSweep {

    static final int BATCH_SIZE = 100;

    private final StripeCustomerRepository stripeCustomerRepository;
    private final StripeCustomerErasureOutboxSupport erasureOutboxSupport;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(cron = "0 17 * * * *")
    @SchedulerLock(name = "StripeCustomerErasureSweep_scheduleMissedErasures", lockAtMostFor = "PT15M", lockAtLeastFor = "PT1M")
    public void scheduleMissedErasures() {
        List<Long> payers = stripeCustomerRepository.findErasedPayersWithoutScheduledErasure(BATCH_SIZE);
        int scheduled = 0;
        for (Long userId : payers) {
            try {
                transactionTemplate.executeWithoutResult(status -> erasureOutboxSupport.scheduleErasure(userId));
                scheduled++;
                log.warn("[STRIPE_CUSTOMER_ERASURE_SCHEDULED_BY_SWEEP userId={}] erased account whose Stripe erasure was "
                    + "never enqueued", userId);
            } catch (RuntimeException e) {
                log.error("[STRIPE_CUSTOMER_ERASURE_SWEEP_FAILED userId={}] retried on the next run", userId, e);
            }
        }
        if (scheduled > 0) {
            log.warn("[STRIPE_CUSTOMER_ERASURE_SWEEP] scheduled {} missed Stripe erasure(s)", scheduled);
        }
    }
}
