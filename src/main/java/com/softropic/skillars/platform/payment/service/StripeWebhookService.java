package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.config.PaymentProperties;
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
import com.stripe.model.Charge;
import com.stripe.model.Dispute;
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

    /** Event types the Connect endpoint acts on (and the only ones it needs to be subscribed to). */
    public static final Set<String> CONNECT_EVENT_TYPES = Set.of("account.updated");

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
    private final CoachStripeAccountStatusApplier accountStatusApplier;

    private Counter invoicePaymentFailedCounter;

    @jakarta.annotation.PostConstruct
    void initializeCounters() {
        invoicePaymentFailedCounter = Counter.builder(INVOICE_PAYMENT_FAILED_COUNTER).register(meterRegistry);
    }

    @Autowired
    @Lazy
    private SubscriptionService subscriptionService;

    /** fix/payment-payouts-disputes-webhooks: chargebacks and refunds made outside the app. */
    @Autowired
    private ChargeDisputeWebhookService chargeDisputeWebhookService;

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
        Event event = verifySignature(payload, sigHeader, paymentProperties.getWebhookSecret());
        self.handleEventAtomically(event);
    }

    /**
     * fix/payment-stripe-connect-webhook-endpoint: entry point for the Stripe <em>Connect</em> webhook
     * endpoint ("events on connected accounts"). Stripe signs it with that endpoint's own secret
     * ({@code app.payment.stripe.connect-webhook-secret}), distinct from the platform-account
     * endpoint's. Only {@link #CONNECT_EVENT_TYPES} are acted on; see {@link #handleConnectEventAtomically}.
     */
    public void processConnectWebhook(String payload, String sigHeader) {
        if (sigHeader == null || sigHeader.isBlank()) {
            throw new WebhookSignatureException("payment.webhookSignatureInvalid");
        }
        String secret = paymentProperties.getConnectWebhookSecret();
        if (secret == null || secret.isBlank()) {
            // Fail closed and loud: a 400 keeps Stripe retrying (for days) while the operator sets
            // APP_PAYMENT_STRIPE_CONNECT_WEBHOOK_SECRET, instead of silently accepting forged events.
            log.error("[STRIPE_CONNECT_WEBHOOK_SECRET_MISSING] app.payment.stripe.connect-webhook-secret is blank; "
                + "rejecting Connect webhook delivery");
            throw new WebhookSignatureException("payment.webhookSignatureInvalid");
        }
        Event event = verifySignature(payload, sigHeader, secret);
        self.handleConnectEventAtomically(event);
    }

    /**
     * Connect-endpoint counterpart of {@link #handleEventAtomically}, with the same idempotency record
     * ({@code payment.stripe_webhook_events}, keyed on the globally unique event id) and the same
     * duplicate handling. Coaches are Standard accounts with their own Stripe business, so the
     * Connect endpoint can also carry the coach's <em>own</em> subscriptions, invoices, charges and
     * disputes. Those are not this platform's objects (bookings are charged on the platform account
     * and paid out by Transfer), so only account-scoped types are processed here; anything else is
     * recorded and acknowledged.
     */
    @Transactional
    public void handleConnectEventAtomically(Event event) {
        int inserted = webhookEventRepository.insertIfAbsent(event.getId(), event.getType());
        if (inserted == 0) {
            log.debug("[STRIPE_WEBHOOK_DUPLICATE id={}]", event.getId());
            return;
        }
        if ("account.updated".equals(event.getType())) {
            handleAccountUpdated(event);
        } else {
            // Not in CONNECT_EVENT_TYPES: the coach's own business, not this platform's.
            log.debug("[STRIPE_CONNECT_WEBHOOK_IGNORED_TYPE type={} account={}]", event.getType(), event.getAccount());
        }
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
        } else if ("charge.dispute.created".equals(event.getType())) {
            // fix/payment-payouts-disputes-webhooks: chargebacks and refunds made outside the app.
            chargeDisputeWebhookService.onDisputeCreated(deserialize(event, Dispute.class));
        } else if ("charge.dispute.closed".equals(event.getType())) {
            chargeDisputeWebhookService.onDisputeClosed(deserialize(event, Dispute.class));
        } else if ("charge.refunded".equals(event.getType())) {
            chargeDisputeWebhookService.onChargeRefunded(deserialize(event, Charge.class), event);
        } else if ("invoice.paid".equals(event.getType())) {
            handleInvoicePaid(event);
        } else {
            log.debug("[STRIPE_WEBHOOK_UNKNOWN_TYPE type={}]", event.getType());
        }
    }

    private Event verifySignature(String payload, String sigHeader, String secret) {
        try {
            return Webhook.constructEvent(payload, sigHeader, secret);
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
        // A Connect delivery names the connected account in event.account; for account.updated that is
        // the same id as the payload object. A mismatch is a malformed or misrouted event: ignore it
        // rather than apply one account's snapshot to another account's row.
        if (event.getAccount() != null && !event.getAccount().equals(acct.getId())) {
            log.warn("[STRIPE_ACCOUNT_UPDATED_ACCOUNT_MISMATCH eventId={} eventAccount={} objectId={}] ignored",
                event.getId(), event.getAccount(), acct.getId());
            return;
        }
        Instant eventCreated = event.getCreated() != null ? Instant.ofEpochSecond(event.getCreated()) : null;
        coachStripeAccountRepository.findByStripeAccountId(acct.getId()).ifPresent(coachAccount ->
            accountStatusApplier.apply(coachAccount, acct, eventCreated, "webhook:" + event.getId()));
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

        Map<String, Object> data = buildSubDataMap(sub, event);
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

        Map<String, Object> data = buildSubDataMap(sub, event);
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
        subscriptionService.handleSubscriptionWebhook("invoice.payment_failed", stripeSubId, eventCreatedData(event));
    }

    /**
     * fix/payment-subscription-entitlements: a paid subscription invoice returns a PAST_DUE/UNPAID
     * subscription to ACTIVE and clears pastDueSince (SubscriptionService.handleInvoicePaid). The
     * Stripe webhook endpoint must be subscribed to {@code invoice.paid}. Same deserialize-or-rollback
     * contract as {@link #handleInvoicePaymentFailed}; an invoice for an unknown subscription is a no-op
     * (no orphan alert: a paid invoice is not a drift signal).
     */
    private void handleInvoicePaid(Event event) {
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
            log.debug("[STRIPE_WEBHOOK_INVOICE_NO_SUBSCRIPTION invoiceId={}]", invoice.getId());
            return;
        }
        subscriptionService.handleSubscriptionWebhook("invoice.paid", stripeSubId, eventCreatedData(event));
    }

    /** The Stripe event's {@code created} (epoch seconds), for SubscriptionService's out-of-order guard. */
    private static Map<String, Object> eventCreatedData(Event event) {
        Map<String, Object> data = new HashMap<>();
        if (event.getCreated() != null) data.put("event_created", event.getCreated());
        return data;
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

    /**
     * fix/payment-payouts-disputes-webhooks: same contract as the other deserializers here — a
     * deserialization failure re-throws so the idempotency row rolls back and Stripe retries.
     */
    private <T> T deserialize(Event event, Class<T> type) {
        try {
            Object obj = event.getDataObjectDeserializer().deserializeUnsafe();
            if (!type.isInstance(obj)) {
                throw new IllegalStateException("expected " + type.getSimpleName() + " for " + event.getType());
            }
            return type.cast(obj);
        } catch (EventDataObjectDeserializationException e) {
            log.error("[STRIPE_WEBHOOK_DESERIALIZE_FAILED type={} error={}]", event.getType(), e.getMessage(), e);
            throw new RuntimeException("Webhook deserialization failed for type " + event.getType(), e);
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

    private Map<String, Object> buildSubDataMap(Subscription sub, Event event) {
        Map<String, Object> data = eventCreatedData(event);
        if (sub.getCurrentPeriodEnd() != null) data.put("current_period_end", sub.getCurrentPeriodEnd());
        if (sub.getCancelAtPeriodEnd() != null) data.put("cancel_at_period_end", sub.getCancelAtPeriodEnd());
        if (sub.getStatus() != null) data.put("status", sub.getStatus());
        return data;
    }
}
