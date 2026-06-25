package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.config.PaymentProperties;
import com.softropic.skillars.platform.payment.contract.event.CoachStripeOnboardingCompleteEvent;
import com.softropic.skillars.platform.payment.contract.exception.WebhookSignatureException;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccountRepository;
import com.softropic.skillars.platform.payment.repo.PaymentCoachSubscriptionRepository;
import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscriptionRepository;
import com.softropic.skillars.platform.payment.repo.StripeWebhookEventRepository;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Account;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.Subscription;
import com.stripe.net.Webhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class StripeWebhookService {

    private final CoachStripeAccountRepository coachStripeAccountRepository;
    private final StripeWebhookEventRepository webhookEventRepository;
    private final PaymentProperties paymentProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final PaymentCoachSubscriptionRepository paymentCoachSubscriptionRepository;
    private final PaymentPlayerSubscriptionRepository paymentPlayerSubscriptionRepository;

    @Autowired
    @Lazy
    private SubscriptionService subscriptionService;

    /** Self-reference so @Transactional on handleEventAtomically is honoured via the Spring proxy. */
    @Autowired
    @Lazy
    private StripeWebhookService self;

    /**
     * Entry point: signature verification is outside @Transactional.
     * Event persistence and processing are committed atomically via self.handleEventAtomically.
     */
    public void processWebhook(String payload, String sigHeader) {
        if (sigHeader == null || sigHeader.isBlank()) {
            throw new WebhookSignatureException("payment.webhookSignatureInvalid");
        }
        Event event = verifySignature(payload, sigHeader);
        self.handleEventAtomically(event);
    }

    /**
     * Atomically inserts the idempotency record and processes the event in one transaction.
     * insertIfAbsent uses ON CONFLICT DO NOTHING so concurrent Stripe retries are safe.
     * Must be public so the Spring CGLIB proxy can intercept it.
     */
    @Transactional
    public void handleEventAtomically(Event event) {
        int inserted = webhookEventRepository.insertIfAbsent(event.getId(), event.getType());
        if (inserted == 0) {
            log.debug("[STRIPE_WEBHOOK_DUPLICATE id={}]", event.getId());
            return;
        }
        if ("account.updated".equals(event.getType())) {
            handleAccountUpdated(event);
        } else if ("customer.subscription.updated".equals(event.getType())) {
            handleSubscriptionUpdated(event);
        } else if ("customer.subscription.deleted".equals(event.getType())) {
            handleSubscriptionDeleted(event);
        } else if ("invoice.payment_failed".equals(event.getType())) {
            handleInvoicePaymentFailed(event);
        } else {
            log.debug("[STRIPE_WEBHOOK_UNKNOWN_TYPE type={}]", event.getType());
        }
    }

    private Event verifySignature(String payload, String sigHeader) {
        try {
            return Webhook.constructEvent(payload, sigHeader, paymentProperties.getWebhookSecret());
        } catch (SignatureVerificationException e) {
            throw new WebhookSignatureException("payment.webhookSignatureInvalid", e);
        }
    }

    private void handleAccountUpdated(Event event) {
        Account acct;
        try {
            acct = (Account) event.getDataObjectDeserializer().deserializeUnsafe();
        } catch (EventDataObjectDeserializationException e) {
            // P24: re-throw so the enclosing @Transactional rolls back the idempotency record —
            // otherwise the record is committed but the account is never updated (permanent data loss).
            // Stripe retries on 500 until the SDK is updated to match the webhook API version.
            log.error("[STRIPE_WEBHOOK_DESERIALIZE_FAILED type={} error={}]", event.getType(), e.getMessage(), e);
            throw new RuntimeException("Webhook deserialization failed for type " + event.getType(), e);
        }
        if (acct == null) {
            log.warn("[STRIPE_WEBHOOK_NULL_ACCOUNT type={}]", event.getType());
            return;
        }
        coachStripeAccountRepository.findByStripeAccountId(acct.getId()).ifPresent(coachAccount -> {
            boolean charges = Boolean.TRUE.equals(acct.getChargesEnabled());
            boolean payouts = Boolean.TRUE.equals(acct.getPayoutsEnabled());
            coachAccount.setChargesEnabled(charges);
            coachAccount.setPayoutsEnabled(payouts);
            if (charges && payouts) {
                coachAccount.setOnboardingStatus("COMPLETE");
                // P23: publish after TX commit so listeners see the persisted COMPLETE status
                UUID coachIdToNotify = coachAccount.getCoachId();
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            eventPublisher.publishEvent(new CoachStripeOnboardingCompleteEvent(coachIdToNotify));
                        }
                    });
                } else {
                    eventPublisher.publishEvent(new CoachStripeOnboardingCompleteEvent(coachIdToNotify));
                }
            } else {
                coachAccount.setOnboardingStatus("RESTRICTED");
            }
            log.info("[STRIPE_ACCOUNT_UPDATED stripeAccountId={} chargesEnabled={} payoutsEnabled={} status={}]",
                acct.getId(), charges, payouts, coachAccount.getOnboardingStatus());
        });
    }

    private void handleSubscriptionUpdated(Event event) {
        Subscription sub = deserializeSubscription(event);
        if (sub == null) return;

        String stripeSubId = sub.getId();
        boolean coachFound = paymentCoachSubscriptionRepository.findByStripeSubscriptionId(stripeSubId).isPresent();
        boolean playerFound = paymentPlayerSubscriptionRepository.findByStripeSubscriptionId(stripeSubId).isPresent();
        if (!coachFound && !playerFound) {
            log.warn("[STRIPE_WEBHOOK_ORPHANED_SUBSCRIPTION stripeSubId={}]", stripeSubId);
            return;
        }

        Map<String, Object> data = buildSubDataMap(sub);
        subscriptionService.handleSubscriptionWebhook("customer.subscription.updated", stripeSubId, data);
    }

    private void handleSubscriptionDeleted(Event event) {
        Subscription sub = deserializeSubscription(event);
        if (sub == null) return;

        String stripeSubId = sub.getId();
        boolean coachFound = paymentCoachSubscriptionRepository.findByStripeSubscriptionId(stripeSubId).isPresent();
        boolean playerFound = paymentPlayerSubscriptionRepository.findByStripeSubscriptionId(stripeSubId).isPresent();
        if (!coachFound && !playerFound) {
            log.warn("[STRIPE_WEBHOOK_ORPHANED_SUBSCRIPTION stripeSubId={}]", stripeSubId);
            return;
        }

        Map<String, Object> data = buildSubDataMap(sub);
        subscriptionService.handleSubscriptionWebhook("customer.subscription.deleted", stripeSubId, data);
    }

    private void handleInvoicePaymentFailed(Event event) {
        Invoice invoice;
        try {
            invoice = (Invoice) event.getDataObjectDeserializer().deserializeUnsafe();
        } catch (EventDataObjectDeserializationException e) {
            log.error("[STRIPE_WEBHOOK_DESERIALIZE_FAILED type={} error={}]", event.getType(), e.getMessage(), e);
            throw new RuntimeException("Webhook deserialization failed for type " + event.getType(), e);
        }
        if (invoice == null) {
            log.warn("[STRIPE_WEBHOOK_NULL_INVOICE type={}]", event.getType());
            return;
        }
        String stripeSubId = invoice.getSubscription();
        if (stripeSubId == null || stripeSubId.isBlank()) {
            log.warn("[STRIPE_WEBHOOK_INVOICE_NO_SUBSCRIPTION invoiceId={}]", invoice.getId());
            return;
        }
        subscriptionService.handleSubscriptionWebhook("invoice.payment_failed", stripeSubId, Map.of());
    }

    private Subscription deserializeSubscription(Event event) {
        try {
            Subscription sub = (Subscription) event.getDataObjectDeserializer().deserializeUnsafe();
            if (sub == null) {
                log.warn("[STRIPE_WEBHOOK_NULL_SUBSCRIPTION type={}]", event.getType());
            }
            return sub;
        } catch (EventDataObjectDeserializationException e) {
            log.error("[STRIPE_WEBHOOK_DESERIALIZE_FAILED type={} error={}]", event.getType(), e.getMessage(), e);
            throw new RuntimeException("Webhook deserialization failed for type " + event.getType(), e);
        }
    }

    private Map<String, Object> buildSubDataMap(Subscription sub) {
        Map<String, Object> data = new HashMap<>();
        if (sub.getCurrentPeriodEnd() != null) data.put("current_period_end", sub.getCurrentPeriodEnd());
        if (sub.getCancelAtPeriodEnd() != null) data.put("cancel_at_period_end", sub.getCancelAtPeriodEnd());
        if (sub.getStatus() != null) data.put("status", sub.getStatus());
        return data;
    }
}
