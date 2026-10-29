package com.softropic.skillars.platform.payment.service;

import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.Customer;
import com.stripe.model.Invoice;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.model.Refund;
import com.stripe.model.SetupIntent;
import com.stripe.model.Subscription;
import com.stripe.model.Transfer;
import com.stripe.model.TransferReversal;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.InvoicePayParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentListParams;
import com.stripe.param.PaymentIntentRetrieveParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.SetupIntentCreateParams;
import com.stripe.param.SubscriptionCancelParams;
import com.stripe.param.SubscriptionCreateParams;
import com.stripe.param.SubscriptionListParams;
import com.stripe.param.SubscriptionResumeParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.TransferCreateParams;
import com.stripe.param.TransferListParams;
import com.stripe.param.TransferReversalCollectionCreateParams;
import com.stripe.param.TransferReversalCollectionListParams;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Thin wrapper around the Stripe SDK static calls so they can be mocked in unit tests.
 */
@Component
public class StripeClient {

    /**
     * Retrieves a connected account (charges/payouts enabled, capabilities). Used by the onboarding
     * fallback sync when the {@code account.updated} webhook may have been missed.
     */
    public Account retrieveAccount(String stripeAccountId) throws StripeException {
        return Account.retrieve(stripeAccountId);
    }

    /**
     * Creates a Stripe PaymentIntent. The idempotency key must be caller-supplied (this method only
     * receives a pre-built params object, so it cannot derive a key itself).
     */
    public PaymentIntent createPaymentIntent(PaymentIntentCreateParams params, String idempotencyKey)
            throws StripeException {
        RequestOptions options = RequestOptions.builder()
            .setIdempotencyKey(idempotencyKey)
            .build();
        return PaymentIntent.create(params, options);
    }

    public PaymentIntent retrievePaymentIntent(String id) throws StripeException {
        return PaymentIntent.retrieve(id);
    }

    /**
     * fix/payment-charge-idempotency: the customer's PaymentIntents created at or after {@code createdGte}
     * (epoch seconds), newest first, auto-paged. List endpoints are strongly consistent (unlike Search), so a
     * PaymentIntent created by a charge whose response was lost is visible here. As with
     * {@link #listSubscriptionsByStatus}, a failure mid-pagination surfaces from the iteration as an
     * unchecked {@code RuntimeException}.
     */
    public Iterable<PaymentIntent> listPaymentIntentsForCustomer(String stripeCustomerId, long createdGte)
            throws StripeException {
        PaymentIntentListParams params = PaymentIntentListParams.builder()
            .setCustomer(stripeCustomerId)
            .setCreated(PaymentIntentListParams.Created.builder().setGte(createdGte).build())
            .setLimit(100L)
            .build();
        return PaymentIntent.list(params).autoPagingIterable();
    }

    /** One round-trip: the PaymentIntent with its latest Charge expanded (refundable-amount check). */
    public PaymentIntent retrievePaymentIntentWithLatestCharge(String id) throws StripeException {
        PaymentIntentRetrieveParams params = PaymentIntentRetrieveParams.builder()
            .addExpand("latest_charge")
            .build();
        return PaymentIntent.retrieve(id, params, RequestOptions.getDefault());
    }

    public Refund createRefund(RefundCreateParams params) throws StripeException {
        return Refund.create(params);
    }

    /**
     * Creates a Stripe Refund under a caller-supplied idempotency key. skillars-deferred-99 AC1:
     * the pack-purchase compensating refund can be re-attempted (a caller retry, or an operator
     * re-driving a recorded failure), and re-issuing a refund double-credits the parent. A stable
     * key per {@code paymentIntentId} lets Stripe replay the original refund instead of creating a
     * second one.
     */
    public Refund createRefund(RefundCreateParams params, String idempotencyKey) throws StripeException {
        RequestOptions options = RequestOptions.builder()
            .setIdempotencyKey(idempotencyKey)
            .build();
        return Refund.create(params, options);
    }

    public SetupIntent createSetupIntent(SetupIntentCreateParams params) throws StripeException {
        return SetupIntent.create(params);
    }

    /**
     * skillars-deferred-106 AC5.2: creates a Stripe {@code Transfer} from the platform balance to a
     * connected account, under a caller-supplied deterministic idempotency key
     * ({@code transfer-{bookingId}}). A re-driven {@code COACH_PAYOUT_TRANSFER} outbox row must
     * replay the original transfer at Stripe, never create a second — same rationale as
     * {@link #createRefund(RefundCreateParams, String)} (skillars-deferred-99 AC1).
     */
    public Transfer createTransfer(TransferCreateParams params, String idempotencyKey) throws StripeException {
        RequestOptions options = RequestOptions.builder()
            .setIdempotencyKey(idempotencyKey)
            .build();
        return Transfer.create(params, options);
    }

    /**
     * fix/payment-payouts-disputes-webhooks: the transfers already made for one {@code transfer_group}
     * (the booking id) — checked before {@code Transfer.create}, because Stripe forgets an idempotency key
     * after 24h and a transfer whose DB commit kept failing for longer would otherwise be made twice.
     */
    public java.util.List<Transfer> listTransfersByGroup(String transferGroup) throws StripeException {
        TransferListParams params = TransferListParams.builder()
            .setTransferGroup(transferGroup)
            .setLimit(10L)
            .build();
        return Transfer.list(params).getData();
    }

    /**
     * skillars-deferred-106 AC5.2: reverses (part of) a previously created {@code Transfer} — the
     * post-payout dispute path. Deterministic key {@code reversal-{stripeTransferId}} so a re-driven
     * {@code COACH_PAYOUT_REVERSAL} outbox row replays rather than double-reversing.
     */
    /**
     * fix/payment-payout-recovery: the reversals already made on a transfer (a handful at most — one per refund
     * on the booking), checked before creating one because Stripe forgets an idempotency key after 24h.
     */
    public java.util.List<TransferReversal> listTransferReversals(String transferId) throws StripeException {
        TransferReversalCollectionListParams params = TransferReversalCollectionListParams.builder()
            .setLimit(100L)
            .build();
        return Transfer.retrieve(transferId).getReversals().list(params).getData();
    }

    public TransferReversal createTransferReversal(String transferId,
                                                   TransferReversalCollectionCreateParams params,
                                                   String idempotencyKey) throws StripeException {
        RequestOptions options = RequestOptions.builder()
            .setIdempotencyKey(idempotencyKey)
            .build();
        return Transfer.retrieve(transferId, options).getReversals().create(params, options);
    }

    public Customer createCustomer(CustomerCreateParams params) throws StripeException {
        return Customer.create(params);
    }

    public PaymentMethod retrievePaymentMethod(String id) throws StripeException {
        return PaymentMethod.retrieve(id);
    }

    public void attachPaymentMethod(String stripeCustomerId, String paymentMethodId) throws StripeException {
        PaymentMethod pm = PaymentMethod.retrieve(paymentMethodId);
        com.stripe.param.PaymentMethodAttachParams attachParams =
            com.stripe.param.PaymentMethodAttachParams.builder()
                .setCustomer(stripeCustomerId)
                .build();
        pm.attach(attachParams);
    }

    /**
     * Creates a Stripe Subscription. Returns the full Subscription so callers can extract
     * subscriptionId and currentPeriodEnd in one call.
     *
     * <p>fix/payment-subscription-entitlements: {@code payment_behavior=error_if_incomplete} — Stripe's
     * default ({@code allow_incomplete}) returned an {@code incomplete} subscription on a declined first
     * payment instead of failing, and the caller granted the paid tier anyway. Now a declined (or
     * SCA-requiring — the frontend has no subscription 3DS flow) first payment throws a
     * {@link com.stripe.exception.CardException} and no subscription is created. The idempotency key
     * is caller-supplied per attempt: the old {@code customer+price} key replayed the original response
     * for any re-subscribe within Stripe's 24h key window.
     */
    public Subscription createSubscription(String stripeCustomerId, String priceId, String paymentMethodId,
                                           String idempotencyKey) throws StripeException {
        RequestOptions options = RequestOptions.builder()
            .setIdempotencyKey(idempotencyKey)
            .build();
        SubscriptionCreateParams params = SubscriptionCreateParams.builder()
            .setCustomer(stripeCustomerId)
            .addItem(SubscriptionCreateParams.Item.builder().setPrice(priceId).build())
            .setDefaultPaymentMethod(paymentMethodId)
            .setPaymentBehavior(SubscriptionCreateParams.PaymentBehavior.ERROR_IF_INCOMPLETE)
            .build();
        return Subscription.create(params, options);
    }

    /**
     * Moves the subscription's single item to a new price.
     *
     * <p>fix/payment-subscription-entitlements: {@code chargeNow=true} (upgrades) uses
     * {@code proration_behavior=always_invoice} + {@code payment_behavior=error_if_incomplete}, so the
     * proration is invoiced and charged immediately and, if that payment fails, Stripe rejects the
     * update (CardException) and leaves the old price in place — previously {@code create_prorations}
     * deferred the charge to the next invoice, so upgrade-then-cancel never paid for the upgrade.
     * {@code chargeNow=false} (downgrades) uses {@code proration_behavior=none}: the current period is
     * already paid at the higher price, the next renewal bills the new one. Idempotency key is
     * caller-supplied (per attempt, or per scheduled change for the retry-safe applicator).
     */
    public Subscription updateSubscriptionTier(String stripeSubscriptionId, String newPriceId, boolean chargeNow,
                                               String idempotencyKey) throws StripeException {
        RequestOptions options = RequestOptions.builder()
            .setIdempotencyKey(idempotencyKey)
            .build();
        Subscription existing = Subscription.retrieve(stripeSubscriptionId);
        String itemId = existing.getItems().getData().get(0).getId();
        SubscriptionUpdateParams.Builder params = SubscriptionUpdateParams.builder()
            .addItem(SubscriptionUpdateParams.Item.builder()
                .setId(itemId)
                .setPrice(newPriceId)
                .build());
        if (chargeNow) {
            params.setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.ALWAYS_INVOICE)
                .setPaymentBehavior(SubscriptionUpdateParams.PaymentBehavior.ERROR_IF_INCOMPLETE);
        } else {
            params.setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.NONE);
        }
        return existing.update(params.build(), options);
    }

    /**
     * Cancels the subscription at period end. Returns the new currentPeriodEnd.
     */
    public Instant cancelSubscriptionAtPeriodEnd(String stripeSubscriptionId) throws StripeException {
        RequestOptions options = RequestOptions.builder()
            .setIdempotencyKey("sub-cancel-" + stripeSubscriptionId)
            .build();
        Subscription sub = Subscription.retrieve(stripeSubscriptionId);
        SubscriptionUpdateParams params = SubscriptionUpdateParams.builder()
            .setCancelAtPeriodEnd(true)
            .build();
        Subscription updated = sub.update(params, options);
        return Instant.ofEpochSecond(updated.getCurrentPeriodEnd());
    }

    /**
     * fix/payment-subscription-payment-method-sync: points the subscription's
     * {@code default_payment_method} at a newly saved card (it otherwise keeps the card it was created
     * with, so "update your card" never reached the subscription).
     */
    public Subscription updateSubscriptionDefaultPaymentMethod(String stripeSubscriptionId, String paymentMethodId)
            throws StripeException {
        RequestOptions options = RequestOptions.builder()
            .setIdempotencyKey("sub-pm-" + stripeSubscriptionId + "-" + paymentMethodId)
            .build();
        Subscription sub = Subscription.retrieve(stripeSubscriptionId);
        return sub.update(SubscriptionUpdateParams.builder().setDefaultPaymentMethod(paymentMethodId).build(), options);
    }

    /**
     * fix/payment-subscription-payment-method-sync: retries the subscription's latest invoice with the
     * given card if it is still {@code open}. Returns the invoice (null when there is none); a decline
     * throws CardException. The per-attempt key lets the user retry with the same card later (a fixed
     * key would replay the earlier decline for 24h). A successful payment arrives as invoice.paid.
     */
    public Invoice payLatestOpenInvoice(String stripeSubscriptionId, String paymentMethodId, String idempotencyKey)
            throws StripeException {
        Subscription sub = Subscription.retrieve(stripeSubscriptionId);
        String invoiceId = sub.getLatestInvoice();
        if (invoiceId == null) return null;
        Invoice invoice = Invoice.retrieve(invoiceId);
        if (!"open".equals(invoice.getStatus())) return invoice;
        RequestOptions options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
        return invoice.pay(InvoicePayParams.builder().setPaymentMethod(paymentMethodId).build(), options);
    }

    /**
     * fix/payment-subscription-resume-and-async-sync: resumes a subscription Stripe paused because its
     * trial ended without a payment method ({@code POST /v1/subscriptions/{id}/resume}). Call it after
     * {@link #updateSubscriptionDefaultPaymentMethod} so the resume invoice is charged to the new card.
     *
     * <p>{@code billing_cycle_anchor=now} starts a fresh period from today and invoices it immediately
     * (a paused subscription delivered nothing during the pause, so the old anchor would bill time the
     * user never had); {@code proration_behavior=none} because there is no unused time to credit.
     * Converges on a retry: a subscription Stripe no longer reports as {@code paused} (an earlier attempt
     * resumed it, or it was cancelled) is returned as-is instead of resumed again, which Stripe rejects.
     * The caller-supplied key is per attempt, like {@link #payLatestOpenInvoice}: it covers the SDK's own
     * network retries without replaying a cached failure on a later attempt. The resulting status change
     * arrives as {@code customer.subscription.updated} / {@code invoice.paid} and drives entitlement.
     */
    public Subscription resumePausedSubscription(String stripeSubscriptionId, String idempotencyKey)
            throws StripeException {
        Subscription sub = Subscription.retrieve(stripeSubscriptionId);
        if (!"paused".equals(sub.getStatus())) return sub;
        RequestOptions options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
        return sub.resume(SubscriptionResumeParams.builder()
            .setBillingCycleAnchor(SubscriptionResumeParams.BillingCycleAnchor.NOW)
            .setProrationBehavior(SubscriptionResumeParams.ProrationBehavior.NONE)
            .build(), options);
    }

    /**
     * fix/payment-subscription-entitlements: cancels the subscription immediately (grace-period expiry,
     * duplicate-create cleanup, a non-active create). Idempotent: a subscription Stripe already reports
     * as {@code canceled}/{@code incomplete_expired} is returned as-is instead of re-cancelled (which
     * Stripe rejects), so a retried sweep converges.
     */
    public Subscription cancelSubscriptionImmediately(String stripeSubscriptionId) throws StripeException {
        Subscription sub = Subscription.retrieve(stripeSubscriptionId);
        if ("canceled".equals(sub.getStatus()) || "incomplete_expired".equals(sub.getStatus())) {
            return sub;
        }
        RequestOptions options = RequestOptions.builder()
            .setIdempotencyKey("sub-cancel-now-" + stripeSubscriptionId)
            .build();
        return sub.cancel(SubscriptionCancelParams.builder().build(), options);
    }

    /**
     * fix/payment-charge-idempotency: refunds what the subscription's latest invoice actually charged — the
     * paid first invoice of a duplicate subscription created by a double-submitted subscribe, which
     * {@link #cancelSubscriptionImmediately} alone kept (the payer paid two periods for one). Returns the
     * refunded amount in minor units, 0 when nothing was paid. Deterministic key
     * {@code sub-dup-refund-{invoiceId}}: a retry replays the refund instead of refunding twice.
     */
    public long refundLatestInvoicePayment(String stripeSubscriptionId) throws StripeException {
        Subscription sub = Subscription.retrieve(stripeSubscriptionId);
        String invoiceId = sub.getLatestInvoice();
        if (invoiceId == null) return 0L;
        Invoice invoice = Invoice.retrieve(invoiceId);
        long paid = invoice.getAmountPaid() == null ? 0L : invoice.getAmountPaid();
        String paymentIntentId = invoice.getPaymentIntent();
        if (paid <= 0 || paymentIntentId == null) return 0L;
        RequestOptions options = RequestOptions.builder()
            .setIdempotencyKey("sub-dup-refund-" + invoiceId)
            .build();
        Refund.create(RefundCreateParams.builder().setPaymentIntent(paymentIntentId).setAmount(paid).build(), options);
        return paid;
    }

    /**
     * skillars-deferred-135 AC2: lists every Stripe subscription in a single status, paginated, for
     * {@code SubscriptionService}'s own scheduled Stripe→payment reconciliation sweep. The pinned SDK
     * (stripe-java 28.4.0, confirmed via {@code javap} against the actual jar)'s {@code
     * SubscriptionListParams$Status} filter accepts exactly ONE status per call — not the 3-way
     * {@code LIVE_SUBSCRIPTION_STATUSES} set {@link StripeWebhookService} filters by — so the sweep
     * calls this once per status in that set and merges the results; see the sweep's own Javadoc for
     * why a single {@code ALL}-status call plus client-side filtering was not chosen instead (touches
     * every canceled subscription too, most of which vastly outnumber the live ones over time).
     *
     * <p>{@code autoPagingIterable()} (the SDK's own standard pagination mechanism, confirmed present
     * on this pinned version) fetches lazily — a {@link StripeException} raised by Stripe mid-pagination
     * surfaces from the RETURNED {@code Iterable}'s own iteration, not from this method's own {@code
     * throws} clause, wrapped by the SDK itself in an unchecked {@code RuntimeException} (confirmed via
     * {@code javap -c} against {@code PagingIterator}'s own bytecode: {@code catch (Exception e) throw
     * new RuntimeException("Unable to lazy-load stripe objects", e)}) — the caller's own iteration loop
     * must be prepared to catch that unchecked wrapper, not just this method's checked one.
     */
    public Iterable<Subscription> listSubscriptionsByStatus(SubscriptionListParams.Status status)
            throws StripeException {
        SubscriptionListParams params = SubscriptionListParams.builder()
            .setStatus(status)
            .setLimit(100L)
            .build();
        return Subscription.list(params).autoPagingIterable();
    }
}
