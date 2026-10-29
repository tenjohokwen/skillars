package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.contract.event.CoachStripeOnboardingCompleteEvent;
import com.softropic.skillars.platform.payment.contract.event.PaymentIntegrityAlertEvent;
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

    /**
     * Local sentinel stored in {@code transfers_capability} once the coach has disconnected the
     * platform ({@code account.application.deauthorized}). Stripe's own values are {@code active} /
     * {@code inactive} / {@code pending}; anything but {@code active} already makes
     * {@code StripePaymentGateway.resolveTransferDestination} refuse the transfer non-retryably, so
     * the payout goes to HOLD (with its PAYOUT_HELD alert) instead of looping. Cleared by the next
     * OAuth connect ({@code StripeOnboardingService.upsertCoachStripeAccount}). Avoids a migration:
     * {@code onboarding_status}'s CHECK allows only PENDING / COMPLETE / RESTRICTED.
     */
    public static final String TRANSFERS_DEAUTHORIZED = "deauthorized";

    /** Admin alert reason prefix; AdminQueueService.STRIPE_DEAUTHORIZED_REASON matches on it. */
    static final String DEAUTHORIZED_ALERT_REASON = "STRIPE_DEAUTHORIZED";

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
        // Once disconnected, only a new OAuth connect (which clears the sentinel) re-enables the
        // account: Stripe sends nothing newer for a revoked connection, so any snapshot is old.
        if (isDeauthorized(coachAccount)) {
            log.info("[STRIPE_ACCOUNT_UPDATED_AFTER_DEAUTHORIZATION stripeAccountId={} source={}] ignored",
                acct.getId(), source);
            return false;
        }
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
            // fix/payment-payout-recovery: a transfer needs only the `transfers` capability (see
            // StripePaymentGateway.transferReadiness), so an active capability on an otherwise restricted account
            // must also wake the HOLD re-drive (CoachPayoutHoldRecoveryService), which re-checks readiness itself.
            if ("active".equalsIgnoreCase(coachAccount.getTransfersCapability())) {
                publishOnboardingCompleteAfterCommit(coachAccount.getCoachId());
            }
        }
        log.info("[STRIPE_ACCOUNT_UPDATED stripeAccountId={} source={} chargesEnabled={} payoutsEnabled={} status={}]",
            acct.getId(), source, charges, payouts, coachAccount.getOnboardingStatus());
        return true;
    }

    /**
     * {@code account.application.deauthorized}: the coach revoked the platform's access to their
     * Stripe account, so nothing can be transferred to it any more. The row is kept (the coach may
     * reconnect through OAuth) but made not payable: RESTRICTED, charges/payouts off, transfers
     * capability {@link #TRANSFERS_DEAUTHORIZED}. Raises one coach-level PAYOUT_HELD alert (one OPEN
     * per coach; a redelivery is already stopped by the webhook-event dedup).
     *
     * <p>Ordered like {@link #apply}: a deauthorization created before the last applied snapshot is
     * stale and ignored. In particular a reconnect stamps {@code last_event_created_at} with the
     * connect time, so a late-delivered deauthorization from before the reconnect cannot disconnect
     * the coach again.
     *
     * @return true if applied, false if ignored as stale
     */
    public boolean applyDeauthorization(CoachStripeAccount coachAccount, Instant eventCreatedAt, String source) {
        if (eventCreatedAt != null && coachAccount.getLastEventCreatedAt() != null
                && eventCreatedAt.isBefore(coachAccount.getLastEventCreatedAt())) {
            log.info("[STRIPE_ACCOUNT_DEAUTHORIZED_STALE stripeAccountId={} source={} created={} lastApplied={}] ignored",
                coachAccount.getStripeAccountId(), source, eventCreatedAt, coachAccount.getLastEventCreatedAt());
            return false;
        }
        if (eventCreatedAt != null) {
            coachAccount.setLastEventCreatedAt(eventCreatedAt);
        }
        coachAccount.setOnboardingStatus(STATUS_RESTRICTED);
        coachAccount.setChargesEnabled(false);
        coachAccount.setPayoutsEnabled(false);
        coachAccount.setTransfersCapability(TRANSFERS_DEAUTHORIZED);
        log.warn("[STRIPE_ACCOUNT_DEAUTHORIZED coachId={} stripeAccountId={} source={}] coach disconnected the "
            + "platform; payouts to this account go to HOLD until the coach reconnects",
            coachAccount.getCoachId(), coachAccount.getStripeAccountId(), source);
        // Published inside the transaction; the admin listener inserts the alert AFTER_COMMIT.
        eventPublisher.publishEvent(new PaymentIntegrityAlertEvent(this, PaymentIntegrityAlertEvent.Kind.PAYOUT_HELD,
            coachAccount.getCoachId().toString(), PaymentIntegrityAlertEvent.Reference.COACH,
            DEAUTHORIZED_ALERT_REASON + " " + coachAccount.getStripeAccountId()));
        return true;
    }

    /** True once the coach has disconnected the platform and has not reconnected since. */
    public static boolean isDeauthorized(CoachStripeAccount coachAccount) {
        return TRANSFERS_DEAUTHORIZED.equals(coachAccount.getTransfersCapability());
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
