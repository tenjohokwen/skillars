package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.config.PaymentProperties;
import com.softropic.skillars.platform.payment.contract.event.CoachStripeOnboardingCompleteEvent;
import com.softropic.skillars.platform.payment.contract.event.CoachSubscriptionOrphanedEvent;
import com.softropic.skillars.platform.payment.contract.exception.WebhookSignatureException;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccountRepository;
import com.softropic.skillars.platform.payment.repo.PaymentCoachSubscription;
import com.softropic.skillars.platform.payment.repo.PaymentCoachSubscriptionRepository;
import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscriptionRepository;
import com.softropic.skillars.platform.payment.repo.StripeCustomer;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.payment.repo.StripeWebhookEventRepository;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Account;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.Subscription;
import com.stripe.net.Webhook;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class StripeWebhookService {

    private static final String INVOICE_PAYMENT_FAILED_COUNTER = "subscription.payment.invoice_failed";

    // skillars-deferred-133 AC3: statuses this project's own SubscriptionService.normalizeStripeStatus
    // maps to ACTIVE/TRIALLING/PAST_DUE — a subscription in one of these is genuinely live/billing, so
    // an orphan here (no local match) is the ledger's actual concern. Deliberately excludes `canceled`/
    // `incomplete_expired`/anything else: SubscriptionService.handleSubscriptionDeleted deliberately
    // nulls stripeSubscriptionId on every normal cancellation (both coach and player sides), so this
    // "orphan" branch is also the normal post-cancellation state — alerting there would generate
    // permanently-unresolvable false positives (no generic way to clear an AdminAlert once raised, see
    // AdminAlertRepository).
    // skillars-deferred-135 AC2: package-visible (not private) so SubscriptionService's own scheduled
    // reconciliation sweep can derive the identical Stripe SubscriptionListParams.Status values from
    // this SAME set (see StripeClient.listSubscriptionsByStatus's own Javadoc for why Stripe's list
    // API needs one status per call) instead of duplicating this 3-status literal set a second time.
    static final Set<String> LIVE_SUBSCRIPTION_STATUSES = Set.of("active", "trialing", "past_due");

    // skillars-deferred-133 AC3: subscribeCoach commits a placeholder payment.coach_subscriptions row
    // (coachId set, stripeSubscriptionId null) BEFORE its own Stripe call, then links
    // stripeSubscriptionId in a later, separate transaction (persistCoachSubscription) — a
    // customer.subscription.updated firing when Stripe transitions the new subscription to `active`
    // (the same status this fix alerts on) can be delivered inside that window, finding no local match
    // for a subscription that is, in fact, healthy and settling normally. Deliberately generous: a
    // genuine happy-path settle completes in well under a second — this only needs to rule out the
    // provisioning race, not compress it.
    private static final Duration SUBSCRIBE_RACE_GRACE_WINDOW = Duration.ofMinutes(10);

    private final CoachStripeAccountRepository coachStripeAccountRepository;
    private final StripeWebhookEventRepository webhookEventRepository;
    private final PaymentProperties paymentProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final PaymentCoachSubscriptionRepository paymentCoachSubscriptionRepository;
    private final PaymentPlayerSubscriptionRepository paymentPlayerSubscriptionRepository;
    private final StripeCustomerRepository stripeCustomerRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final MeterRegistry meterRegistry;

    private Counter invoicePaymentFailedCounter;

    @jakarta.annotation.PostConstruct
    void initializeCounters() {
        invoicePaymentFailedCounter = Counter.builder(INVOICE_PAYMENT_FAILED_COUNTER).register(meterRegistry);
    }

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
            maybeAlertOrphanedLiveSubscription(sub);
            return;
        }

        Map<String, Object> data = buildSubDataMap(sub);
        subscriptionService.handleSubscriptionWebhook("customer.subscription.updated", stripeSubId, data);
    }

    /**
     * skillars-deferred-133 AC3. Scoped to coaches only, matching {@code SubscriptionService.
     * syncMarketplaceTier}'s own scope — there is no "Stripe → payment" ledger item for players. See
     * {@link #LIVE_SUBSCRIPTION_STATUSES} and {@link #SUBSCRIBE_RACE_GRACE_WINDOW}'s own Javadoc for
     * the two false-positive sources this method guards against.
     *
     * <p>Deliberately wrapped in its own {@code catch (Exception e)} rather than left to propagate:
     * a deterministic failure here (an enum/CHECK-constraint mismatch, an unexpected exception from
     * either repository) would otherwise roll back {@code handleEventAtomically}'s
     * {@code insertIfAbsent} idempotency row and put Stripe into an indefinite retry loop on every
     * delivery of this event — this fix must never be the reason a routine webhook stops being
     * accepted. This is deliberate double protection alongside {@code AdminAlertEventListener.
     * onCoachSubscriptionOrphaned}'s own {@code REQUIRES_NEW}, not a substitute for it.
     */
    private void maybeAlertOrphanedLiveSubscription(Subscription sub) {
        try {
            if (sub.getStatus() == null || !LIVE_SUBSCRIPTION_STATUSES.contains(sub.getStatus())) {
                return;
            }
            resolveCoachAndAlertIfOrphaned(sub.getCustomer(), sub.getId());
        } catch (Exception e) {
            log.warn("[STRIPE_WEBHOOK_ORPHAN_ALERT_FAILED stripeSubId={}]", sub.getId(), e);
        }
    }

    /**
     * skillars-deferred-135 AC2: extracted from {@link #maybeAlertOrphanedLiveSubscription} and
     * {@link #maybeAlertOrphanedInvoicePaymentFailed} — both webhook-path callers shared this identical
     * resolution chain before this extraction — so {@code SubscriptionService}'s own new scheduled
     * reconciliation sweep can reuse it too, rather than duplicating the chain a third time (per this
     * story's own explicit instruction). Package-visible, not private: {@code SubscriptionService} calls
     * it via a plain (non-{@code @Lazy}) constructor-injected {@code StripeWebhookService} dependency —
     * safe against a circular-construction issue because THIS class's own reverse dependency on {@code
     * SubscriptionService} is {@code @Lazy} field-injected (see {@link #subscriptionService}'s own
     * field comment), so this class's constructor never needs {@code SubscriptionService} to exist
     * first.
     *
     * <p>Deliberately starts from "I already have a {@code stripeCustomerId} and {@code stripeSubId}
     * worth checking" — both existing webhook-path callers' own preconditions (live-status allowlist,
     * customer-null guard) happen at the caller, since the sweep's own preconditions differ (it already
     * filters to live-status subscriptions via {@code StripeClient#listSubscriptionsByStatus}'s own
     * per-status Stripe API calls, and Stripe subscriptions always carry a customer id) — this method
     * itself only needs a null check on {@code stripeCustomerId}, not a duplicate live-status check.
     */
    void resolveCoachAndAlertIfOrphaned(String stripeCustomerId, String stripeSubId) {
        if (stripeCustomerId == null) {
            return;
        }
        List<StripeCustomer> stripeCustomers = stripeCustomerRepository.findByStripeCustomerId(stripeCustomerId);
        if (stripeCustomers.isEmpty()) {
            return;
        }
        // StripeCustomer.parentId is the coach/parent's own main.user.id (despite the field's
        // name) — see StripeCustomer's own class Javadoc and SubscriptionService.subscribeCoach's
        // identical findById(coachUserId) lookup.
        Long userId = stripeCustomers.get(0).getParentId();
        Optional<CoachProfile> coachProfile = coachProfileRepository.findByUserId(userId);
        if (coachProfile.isEmpty()) {
            // A player, or a genuinely unrecognized customer — this fix is scoped to coaches only,
            // a final decision, not a residual left open (see this method's own Javadoc).
            return;
        }
        UUID coachId = coachProfile.get().getId();
        Optional<PaymentCoachSubscription> existing = paymentCoachSubscriptionRepository.findByCoachId(coachId);
        if (existing.isPresent() && existing.get().getUpdatedAt() != null
                && Duration.between(existing.get().getUpdatedAt(), Instant.now())
                    .compareTo(SUBSCRIBE_RACE_GRACE_WINDOW) < 0) {
            log.debug("[STRIPE_WEBHOOK_ORPHAN_GRACE_WINDOW coachId={} stripeSubId={}] recently-"
                + "touched payment.coach_subscriptions row for this coach — treating as still "
                + "settling, not alerting", coachId, stripeSubId);
            return;
        }
        eventPublisher.publishEvent(new CoachSubscriptionOrphanedEvent(this, coachId, stripeSubId));
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
        invoicePaymentFailedCounter.increment();
        // skillars-deferred-134 AC2: checked (and, if orphaned, alerted on) BEFORE delegating, mirroring
        // handleSubscriptionUpdated's own check-then-alert ordering — but unlike that method, this call
        // never skips the delegation below: SubscriptionService.handleInvoicePaymentFailed's own two
        // ifPresent-only lookups are already a no-op for an orphaned subscription today, so there is
        // nothing to skip, matched or not.
        maybeAlertOrphanedInvoicePaymentFailed(invoice, stripeSubId);
        subscriptionService.handleSubscriptionWebhook("invoice.payment_failed", stripeSubId, Map.of());
    }

    /**
     * skillars-deferred-134 AC2. Extends the orphan-alerting {@link #maybeAlertOrphanedLiveSubscription}
     * shipped for {@code customer.subscription.updated} to this event type — the identical underlying
     * gap ({@code SubscriptionService.handleInvoicePaymentFailed}'s two {@code ifPresent}-only lookups
     * are a silent no-op for a Stripe subscription this system has no local row for), reached via a
     * different webhook event. Reuses the same resolution chain, grace window, event type
     * ({@code CoachSubscriptionOrphanedEvent} / {@code AdminAlertType.SUBSCRIPTION_ORPHANED}) rather
     * than minting a new one — {@link AdminAlertEventListener#insertAlert}'s own per-{@code
     * (referenceId, type, OPEN)} dedup (AC1, this story) means a coach already alerted via
     * {@code handleSubscriptionUpdated} won't get a second, redundant alert from this method firing
     * moments later for the same underlying drift.
     *
     * <p><strong>No {@link #LIVE_SUBSCRIPTION_STATUSES} allowlist equivalent is needed here</strong> —
     * {@code invoice.payment_failed} is itself already a live-billing-attempt signal (Stripe only
     * emits it for an actual failed payment attempt on an actual invoice), unlike {@code
     * customer.subscription.updated}, which fires on every status transition including the normal
     * post-cancellation settle that motivated that allowlist in the first place.
     *
     * <p>Wrapped in the same {@code catch (Exception e)} pattern as {@link
     * #maybeAlertOrphanedLiveSubscription}, for the identical reason: a deterministic failure in this
     * alerting path must never roll back {@code handleEventAtomically}'s idempotency-record insert and
     * put Stripe into an indefinite retry loop on a routine webhook.
     */
    private void maybeAlertOrphanedInvoicePaymentFailed(Invoice invoice, String stripeSubId) {
        try {
            boolean coachFound = paymentCoachSubscriptionRepository.findByStripeSubscriptionId(stripeSubId).isPresent();
            boolean playerFound = paymentPlayerSubscriptionRepository.findByStripeSubscriptionId(stripeSubId).isPresent();
            if (coachFound || playerFound) {
                return;
            }
            resolveCoachAndAlertIfOrphaned(invoice.getCustomer(), stripeSubId);
        } catch (Exception e) {
            log.warn("[STRIPE_WEBHOOK_ORPHAN_ALERT_FAILED stripeSubId={}]", stripeSubId, e);
        }
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
