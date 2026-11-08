package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.config.DataSourceConfig;
import com.softropic.skillars.infrastructure.config.RoutingDataSourceContext;
import com.softropic.skillars.platform.payment.repo.StripePendingSubscriptionEventRepository;
import com.softropic.skillars.platform.payment.repo.SubscriptionUpgradeReconciliation;
import com.softropic.skillars.platform.payment.repo.SubscriptionUpgradeReconciliationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * skillars-deferred-169 AC4/AC6: best-effort durable writes that must commit INDEPENDENTLY of the caller's transaction, on the
 * payment {@code REQUIRES_NEW} routed pool (the caller already holds a connection from the primary pool, and ~25 concurrent
 * webhooks could otherwise exhaust it). A separate bean on purpose: {@code REQUIRES_NEW} is silently ignored on in-class
 * self-invocation (see {@code OutboxService}, {@code PendingProviderAssetTracker}). The public methods are NOT transactional: they
 * select the routed pool BEFORE the proxy of the inner method opens its connection, exactly as
 * {@code BookingPaymentPersistenceService.reserveCapture} does. Every failure is logged and swallowed - a bookkeeping write must
 * never fail the webhook or the upgrade request that already moved money.
 *
 * <p>FK-free target tables, on purpose: an FK to the subscription row would block on the {@code FOR UPDATE} the outer
 * transaction holds ({@code persist*TierUpgrade}) and self-deadlock.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentRecoveryRecorder {

    private final StripePendingSubscriptionEventRepository pendingEventRepository;
    private final SubscriptionUpgradeReconciliationRepository upgradeReconciliationRepository;

    @Autowired
    @Lazy
    private PaymentRecoveryRecorder self;

    /** Parks an orphaned subscription event; returns true when the row is durable (inserted now or already present). */
    public boolean parkSubscriptionEvent(ParkedSubscriptionEvent e) {
        RoutingDataSourceContext.set(DataSourceConfig.PAYMENT_REQUIRES_NEW_DATASOURCE_KEY);
        try {
            self.parkSubscriptionEventTx(e);
            return true;
        } catch (RuntimeException ex) {
            log.error("[STRIPE_WEBHOOK_PARK_FAILED] orphaned subscription event dropped: eventId={} stripeSubId={}",
                e.eventId(), e.stripeSubscriptionId(), ex);
            return false;
        } finally {
            RoutingDataSourceContext.clear();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void parkSubscriptionEventTx(ParkedSubscriptionEvent e) {
        pendingEventRepository.insertIfAbsent(e.eventId(), e.stripeSubscriptionId(), e.eventType(), e.eventCreated(),
            e.status(), e.currentPeriodEnd(), e.cancelAtPeriodEnd());
    }

    /** Records a paid upgrade that could not be granted. Never throws. */
    public void recordUngrantedUpgrade(String kind, String subscriberRef, String stripeSubscriptionId, String targetTier,
                                       String latestInvoiceId) {
        RoutingDataSourceContext.set(DataSourceConfig.PAYMENT_REQUIRES_NEW_DATASOURCE_KEY);
        try {
            self.recordUngrantedUpgradeTx(kind, subscriberRef, stripeSubscriptionId, targetTier, latestInvoiceId);
        } catch (RuntimeException ex) {
            // The error-level log line written first by the caller is the alert source; this row is the durable half.
            log.error("[SUBSCRIPTION_UPGRADE_RECORD_FAILED] stripeSubId={} invoiceId={}", stripeSubscriptionId, latestInvoiceId, ex);
        } finally {
            RoutingDataSourceContext.clear();
        }
    }

    /**
     * skillars-deferred-175 AC3: a client retry of a failed upgrade that Stripe did not bill again adds no row while one for the
     * same subscription, target tier and invoice is still open. A retry that produced a new invoice gets its own row, so every
     * billed proration stays visible to the operator. A check, not a constraint: two failures in the same instant may still add two.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUngrantedUpgradeTx(String kind, String subscriberRef, String stripeSubscriptionId, String targetTier,
                                         String latestInvoiceId) {
        if (upgradeReconciliationRepository.existsByStripeSubscriptionIdAndTargetTierAndLatestInvoiceIdAndResolvedAtIsNull(
                stripeSubscriptionId, targetTier, latestInvoiceId)) {
            log.info("Ungranted upgrade already recorded and open, no new row: stripeSubId={} targetTier={} invoiceId={}",
                stripeSubscriptionId, targetTier, latestInvoiceId);
            return;
        }
        upgradeReconciliationRepository.save(new SubscriptionUpgradeReconciliation(
            kind, subscriberRef, stripeSubscriptionId, targetTier, latestInvoiceId));
    }

    /**
     * skillars-deferred-176 AC5 (RS07c): a tier upgrade that was persisted resolves any open row recorded for the same
     * subscription and target tier (an earlier attempt that Stripe billed and the persist lost), so the operator does not
     * refund a grant that a retry completed. Never throws.
     */
    public void resolveUngrantedUpgrades(String stripeSubscriptionId, String targetTier) {
        RoutingDataSourceContext.set(DataSourceConfig.PAYMENT_REQUIRES_NEW_DATASOURCE_KEY);
        try {
            int resolved = self.resolveUngrantedUpgradesTx(stripeSubscriptionId, targetTier);
            if (resolved > 0) {
                log.info("[SUBSCRIPTION_UPGRADE_RECONCILED] a retry granted the tier: stripeSubId={} targetTier={} rows={}",
                    stripeSubscriptionId, targetTier, resolved);
            }
        } catch (RuntimeException ex) {
            log.error("[SUBSCRIPTION_UPGRADE_RESOLVE_FAILED] stripeSubId={} targetTier={}", stripeSubscriptionId, targetTier, ex);
        } finally {
            RoutingDataSourceContext.clear();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int resolveUngrantedUpgradesTx(String stripeSubscriptionId, String targetTier) {
        return upgradeReconciliationRepository.resolveOpen(stripeSubscriptionId, targetTier, java.time.Instant.now());
    }

    /** The extracted fields of a parked {@code customer.subscription.updated|deleted} event. */
    public record ParkedSubscriptionEvent(String eventId, String stripeSubscriptionId, String eventType, Long eventCreated,
                                          String status, Long currentPeriodEnd, Boolean cancelAtPeriodEnd,
                                          boolean persisted) {

        public ParkedSubscriptionEvent withPersisted(boolean value) {
            return new ParkedSubscriptionEvent(eventId, stripeSubscriptionId, eventType, eventCreated, status, currentPeriodEnd,
                cancelAtPeriodEnd, value);
        }
    }
}
