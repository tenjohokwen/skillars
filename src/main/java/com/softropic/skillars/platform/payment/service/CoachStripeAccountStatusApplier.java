package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.contract.event.CoachStripeOnboardingCompleteEvent;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccount;
import com.stripe.model.Account;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.UUID;

/**
 * fix/payment-stripe-connect-webhook-endpoint: the single place that turns a Stripe {@link Account}
 * snapshot into a coach's local Connect status ({@code onboarding_status}, {@code charges_enabled},
 * {@code payouts_enabled}, {@code transfers_capability}, {@code last_event_created_at}).
 *
 * <p>Two callers share it, so both apply identical rules:
 * <ul>
 *   <li>the {@code account.updated} webhook ({@link StripeWebhookService}), with the event's own
 *       {@code created} as the snapshot time;</li>
 *   <li>the onboarding fallback sync ({@link StripeOnboardingService}), which retrieves the account
 *       from Stripe when the OAuth callback returns or while the status is still not
 *       {@code COMPLETE}, so a missed webhook cannot leave a coach stuck in {@code PENDING}.</li>
 * </ul>
 *
 * <p>Not transactional itself: the caller must hold a transaction in which {@code coachAccount} is a
 * managed entity, so the changes are flushed by dirty checking on commit (the row's {@code @Version}
 * turns a concurrent webhook-vs-fallback write into an optimistic-lock failure instead of a lost
 * update).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CoachStripeAccountStatusApplier {

    static final String STATUS_COMPLETE = "COMPLETE";
    static final String STATUS_RESTRICTED = "RESTRICTED";

    private final ApplicationEventPublisher eventPublisher;

    /**
     * Applies {@code acct} to {@code coachAccount} unless a newer snapshot has already been applied.
     *
     * @param snapshotAt when the snapshot was taken (Stripe's event {@code created}, or the fallback's
     *                   conservative fetch time); null means unknown, which always applies and leaves
     *                   {@code last_event_created_at} unchanged
     * @param source     log tag ({@code webhook:evt_…} or {@code fetch})
     * @return true if applied, false if ignored as stale
     */
    public boolean apply(CoachStripeAccount coachAccount, Account acct, Instant snapshotAt, String source) {
        // fix/payment-payouts-disputes-webhooks: Stripe does not deliver events in order — a snapshot
        // older than the one last applied must not overwrite it (same-second events still apply).
        if (snapshotAt != null && coachAccount.getLastEventCreatedAt() != null
                && snapshotAt.isBefore(coachAccount.getLastEventCreatedAt())) {
            log.info("[STRIPE_ACCOUNT_UPDATED_STALE stripeAccountId={} source={} created={} lastApplied={}] ignored",
                acct.getId(), source, snapshotAt, coachAccount.getLastEventCreatedAt());
            return false;
        }
        if (snapshotAt != null) {
            coachAccount.setLastEventCreatedAt(snapshotAt);
        }
        // A Transfer to the account needs its `transfers` capability active (StripePaymentGateway).
        if (acct.getCapabilities() != null) {
            coachAccount.setTransfersCapability(acct.getCapabilities().getTransfers());
        }
        boolean charges = Boolean.TRUE.equals(acct.getChargesEnabled());
        boolean payouts = Boolean.TRUE.equals(acct.getPayoutsEnabled());
        coachAccount.setChargesEnabled(charges);
        coachAccount.setPayoutsEnabled(payouts);
        if (charges && payouts) {
            coachAccount.setOnboardingStatus(STATUS_COMPLETE);
            publishOnboardingCompleteAfterCommit(coachAccount.getCoachId());
        } else {
            coachAccount.setOnboardingStatus(STATUS_RESTRICTED);
        }
        log.info("[STRIPE_ACCOUNT_UPDATED stripeAccountId={} source={} chargesEnabled={} payoutsEnabled={} status={}]",
            acct.getId(), source, charges, payouts, coachAccount.getOnboardingStatus());
        return true;
    }

    /** P23: publish after TX commit so listeners see the persisted COMPLETE status. */
    private void publishOnboardingCompleteAfterCommit(UUID coachId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventPublisher.publishEvent(new CoachStripeOnboardingCompleteEvent(coachId));
                }
            });
        } else {
            eventPublisher.publishEvent(new CoachStripeOnboardingCompleteEvent(coachId));
        }
    }
}
