package com.softropic.skillars.platform.payment.service;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Transfer;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.SetupIntentCreateParams;
import com.stripe.param.TransferCreateParams;
import com.stripe.param.TransferReversalCollectionCreateParams;
import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.payment.contract.ChargeLookup;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.contract.exception.ChargeOutcomeUnknownException;
import com.softropic.skillars.platform.payment.contract.exception.CoachPayoutTransferException;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccount;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccountRepository;
import com.softropic.skillars.platform.payment.repo.StripeCustomer;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class StripePaymentGateway implements PaymentGateway {

    private static final Duration IDEMPOTENCY_KEY_WINDOW = Duration.ofMinutes(1);

    /** fix/payment-payout-recovery: reversal metadata naming its idempotency key, matched before re-creating. */
    static final String REVERSAL_KEY_METADATA = "reversalKey";
    /** fix/payment-payout-recovery: HOLD reasons the automatic HOLD re-drive leaves to an operator. */
    public static final String REASON_AMOUNT_MISMATCH = "TRANSFER_AMOUNT_MISMATCH";
    public static final String REASON_DESTINATION_MISMATCH = "TRANSFER_DESTINATION_MISMATCH";

    /** PaymentIntent metadata key carrying the charge's idempotency key (see findChargeByIdempotencyKey). */
    static final String CHARGE_KEY_METADATA = "chargeKey";

    private static final BigDecimal DEFAULT_MINIMUM_CHARGE = new BigDecimal("0.50");
    private static final Map<String, BigDecimal> MINIMUM_CHARGE_BY_CURRENCY = Map.ofEntries(
        Map.entry("usd", new BigDecimal("0.50")), Map.entry("eur", new BigDecimal("0.50")),
        Map.entry("gbp", new BigDecimal("0.30")), Map.entry("chf", new BigDecimal("0.50")),
        Map.entry("cad", new BigDecimal("0.50")), Map.entry("aud", new BigDecimal("0.50")),
        Map.entry("nzd", new BigDecimal("0.50")), Map.entry("sgd", new BigDecimal("0.50")),
        Map.entry("dkk", new BigDecimal("2.50")), Map.entry("nok", new BigDecimal("3.00")),
        Map.entry("sek", new BigDecimal("3.00")), Map.entry("pln", new BigDecimal("2.00")),
        Map.entry("czk", new BigDecimal("15.00")), Map.entry("huf", new BigDecimal("175.00")),
        Map.entry("ron", new BigDecimal("2.00")), Map.entry("bgn", new BigDecimal("1.00")),
        Map.entry("hkd", new BigDecimal("4.00")), Map.entry("mxn", new BigDecimal("10.00")),
        Map.entry("aed", new BigDecimal("2.00")), Map.entry("brl", new BigDecimal("0.50")),
        Map.entry("inr", new BigDecimal("0.50")), Map.entry("myr", new BigDecimal("2.00")),
        Map.entry("thb", new BigDecimal("10.00")));

    private final CoachStripeAccountRepository coachStripeAccountRepository;
    private final StripeCustomerRepository stripeCustomerRepository;
    private final ConfigService configService;
    private final StripeClient stripeClient;

    /**
     * skillars-deferred-106 (B-1): charge the <strong>platform</strong> account — no
     * {@code transfer_data.destination}, no {@code application_fee_amount}. The full price is held on
     * the platform balance until {@link #transferToCoach} runs on session completion. The commission
     * is retained implicitly by transferring only the net later; it is no longer a Stripe
     * {@code application_fee_amount}, and the rate is stamped into {@code booking_payments.commission_rate}
     * at capture by {@code BookingPaymentPersistenceService} (AC3.5 / AC4.2), never re-read here.
     *
     * <p>AC1.5: the coach's connected account is still checked at booking time (fail fast — do not
     * accept a booking for a coach who can never be paid), but the account id is used only at
     * transfer time now, not on this {@code PaymentIntent}.
     */
    @Override
    public String chargeAndCapture(UUID referenceId, Long parentId, UUID coachId, BigDecimal amount) {
        return charge(referenceId, parentId, coachId, amount, null);
    }

    /**
     * fix/payment-session-pack-integrity: the session-pack purchase passes a per-attempt key, so the tier-id
     * + time-bucket key below (which replayed a sibling's purchase inside one minute and double-charged a
     * retry crossing the minute boundary) no longer applies to it.
     */
    @Override
    public String chargeAndCapture(UUID referenceId, Long parentId, UUID coachId, BigDecimal amount,
                                   String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        return charge(referenceId, parentId, coachId, amount, idempotencyKey);
    }

    private String charge(UUID referenceId, Long parentId, UUID coachId, BigDecimal amount, String explicitKey) {
        // Fail fast: throws payment.coachStripeNotConfigured if the coach has no COMPLETE +
        // charges_enabled connected account. The returned id is intentionally discarded — B-1 does
        // not put it on the PaymentIntent.
        resolveCoachStripeAccountId(coachId);

        String currency;
        try {
            currency = resolveCurrency();
        } catch (IllegalStateException e) {
            log.error("Payment configuration unavailable: error={}", e.getMessage());
            throw new PaymentGatewayException("payment.configurationUnavailable", e);
        }
        long amountCents = toCents(amount);

        PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
            .setAmount(amountCents)
            .setCurrency(currency)
            .setConfirm(true)
            .putMetadata("referenceId", referenceId != null ? referenceId.toString() : "")
            .putMetadata("coachId", coachId.toString())
            // skillars-deferred-106 AC1.2: correlate the later Transfer.create in Stripe.
            .setTransferGroup(referenceId != null ? referenceId.toString() : "");

        StripeCustomer stripeCustomer = null;
        if (parentId != null) {
            stripeCustomer = stripeCustomerRepository.findById(parentId)
                .orElseThrow(() -> new PaymentGatewayException("payment.noStripeCustomer"));
            String paymentMethodId = stripeCustomer.getStripePaymentMethodId();
            if (paymentMethodId == null) {
                throw new PaymentGatewayException("payment.noPaymentMethod");
            }
            builder.setCustomer(stripeCustomer.getStripeCustomerId())
                   .setPaymentMethod(paymentMethodId)
                   .setOffSession(true);
        }

        // fix/payment-charge-idempotency: every live caller now passes an explicit, per-attempt key that it
        // persisted BEFORE this call — "pi-booking-{bookingId}" / "pi-batch-{batchId}-{reserved ids hash}"
        // (PaymentLifecycleService, on the CAPTURE_PENDING rows) or the pack purchase's attempt key — so a
        // lost response can be looked up under the same key. Booking and batch charges used to fall through
        // to the time-bucketed default below: a key that could not be rebuilt after the fact, and that two
        // batch accepts in the same minute shared. The default is kept only for the 4-arg overload (no
        // booking caller left) and the deprecated capturePayment(); the bucket stops a non-unique referenceId
        // (a pack tier id) from replaying an earlier purchase across windows.
        long bucket = Instant.now(ClockProvider.getClock()).getEpochSecond() / IDEMPOTENCY_KEY_WINDOW.toSeconds();
        // parentId is null only via the deprecated capturePayment() overload (no current caller).
        // Fall back to coachId — always non-null — rather than the literal "null", so two
        // distinct null-parentId charges sharing a referenceId don't collide on one key.
        Object keyOwner = parentId != null ? parentId : coachId;
        String idempotencyKey = explicitKey != null ? explicitKey : "pi-" + referenceId + "-" + keyOwner + "-" + bucket;
        // The key travels on the PaymentIntent too, so ChargeOutcomeReconciler can find it by listing the
        // payer's PaymentIntents (findChargeByIdempotencyKey).
        builder.putMetadata(CHARGE_KEY_METADATA, idempotencyKey);
        PaymentIntent intent;
        try {
            intent = stripeClient.createPaymentIntent(builder.build(), idempotencyKey);
        } catch (StripeException e) {
            // Classified the way refund() does. A card decline / 4xx means Stripe rejected the request and
            // created no charge. No response (connection failure, read timeout after the SDK's retries), a 5xx,
            // or an idempotency conflict (409, or IdempotencyException — another request under this key is
            // executing or already ran) means the card MAY have been charged: declining the booking then
            // kept the parent's money with no record. Those surface as ChargeOutcomeUnknownException and the
            // caller leaves the reservation for reconciliation.
            if (isOutcomeUnknown(e)) {
                log.error("[STRIPE_CHARGE_OUTCOME_UNKNOWN] referenceId={} key={} status={} error={} — not a decline; "
                    + "left for reconciliation", referenceId, idempotencyKey, e.getStatusCode(), e.getMessage());
                throw new ChargeOutcomeUnknownException(idempotencyKey, e);
            }
            log.error("Stripe charge failed: referenceId={} status={} error={}", referenceId, e.getStatusCode(),
                e.getMessage());
            throw new PaymentGatewayException("payment.lifecycleFailure", e);
        }
        // fix/payment-charge-idempotency (L5): only "succeeded" is money captured. Off-session card charges
        // return succeeded or throw today, but any other payment-method type could come back processing
        // (captured later, or not) or requires_action (nothing captured) — never confirm a booking on either.
        String status = intent.getStatus();
        if (ChargeLookup.PROCESSING.equals(status)) {
            log.error("[STRIPE_CHARGE_OUTCOME_UNKNOWN] referenceId={} intentId={} status=processing — left for "
                + "reconciliation", referenceId, intent.getId());
            throw new ChargeOutcomeUnknownException(idempotencyKey, null);
        }
        if (!ChargeLookup.SUCCEEDED.equals(status)) {
            log.error("[STRIPE_CHARGE_NOT_SUCCEEDED] referenceId={} intentId={} status={} — nothing captured, "
                + "treated as declined", referenceId, intent.getId(), status);
            throw new PaymentGatewayException("payment.lifecycleFailure");
        }
        log.info("Stripe charge captured (platform account): referenceId={} intentId={}", referenceId, intent.getId());
        if (stripeCustomer != null) {
            // fix/payment-session-pack-integrity: bookkeeping only, AFTER the money is captured. A failure here
            // used to escape before the intent id reached the caller — captured with no record and no
            // compensating refund. Never let it fail the charge.
            try {
                stripeCustomer.setLastPaymentIntentId(intent.getId());
                stripeCustomerRepository.save(stripeCustomer);
            } catch (RuntimeException e) {
                log.error("[STRIPE_LAST_INTENT_SAVE_FAILED] charge captured but stripe_customers.last_payment_intent_id "
                    + "not updated: parentId={} intentId={}", parentId, intent.getId(), e);
            }
        }
        return intent.getId();
    }

    @Override
    public String chargeAndCaptureForBatch(UUID batchId, Long parentId, UUID coachId, BigDecimal amount,
                                           String idempotencyKey) {
        return chargeAndCapture(batchId, parentId, coachId, amount, idempotencyKey);
    }

    /**
     * No HTTP response at all (ApiConnectionException carries status 0 / null), a 5xx, or an idempotency
     * conflict: Stripe may have executed the charge. Mirrors the refund() classification.
     */
    static boolean isOutcomeUnknown(StripeException e) {
        Integer status = e.getStatusCode();
        return e instanceof ApiConnectionException || e instanceof IdempotencyException
            || status == null || status == 0 || status == 409 || status >= 500;
    }

    /**
     * fix/payment-charge-idempotency: finds the PaymentIntent a booking charge was sent under, by its
     * {@code chargeKey} metadata among the payer's PaymentIntents. A payer with no Stripe customer could not
     * have been charged (charge() refuses before calling Stripe), so that is a proven absence.
     */
    @Override
    public Optional<ChargeLookup> findChargeByIdempotencyKey(Long parentId, String idempotencyKey,
                                                             Instant createdAfter) {
        StripeCustomer customer = stripeCustomerRepository.findById(parentId).orElse(null);
        if (customer == null || customer.getStripeCustomerId() == null) {
            return Optional.empty();
        }
        try {
            for (PaymentIntent pi : stripeClient.listPaymentIntentsForCustomer(customer.getStripeCustomerId(),
                    createdAfter.getEpochSecond())) {
                if (pi.getMetadata() != null && idempotencyKey.equals(pi.getMetadata().get(CHARGE_KEY_METADATA))) {
                    return Optional.of(toLookup(pi));
                }
            }
            return Optional.empty();
        } catch (StripeException | RuntimeException e) {
            // RuntimeException: auto-paging wraps a mid-pagination StripeException in an unchecked one.
            log.error("Stripe charge lookup failed: parentId={} key={} error={}", parentId, idempotencyKey, e.getMessage());
            throw new PaymentGatewayException("payment.chargeLookupFailed", e);
        }
    }

    @Override
    public ChargeLookup retrieveCharge(String paymentIntentId) {
        try {
            return toLookup(stripeClient.retrievePaymentIntent(paymentIntentId));
        } catch (StripeException e) {
            log.error("Stripe charge lookup failed: intentId={} error={}", paymentIntentId, e.getMessage());
            throw new PaymentGatewayException("payment.chargeLookupFailed", e);
        }
    }

    private static ChargeLookup toLookup(PaymentIntent pi) {
        BigDecimal amount = pi.getAmount() == null ? null : BigDecimal.valueOf(pi.getAmount(), 2);
        return new ChargeLookup(pi.getId(), pi.getStatus(), amount);
    }

    /**
     * fix/payment-charge-idempotency (L3): Stripe's documented minimum charge per settlement currency
     * (https://docs.stripe.com/currencies#minimum-and-maximum-charge-amounts), in major units. Unknown
     * currencies fall back to 0.50, the minimum of most. An unreadable currency config is not this method's
     * failure to report (the charge itself raises payment.configurationUnavailable): 0.50 again.
     */
    @Override
    public BigDecimal minimumCardCharge() {
        String currency;
        try {
            currency = resolveCurrency();
        } catch (RuntimeException e) {
            return DEFAULT_MINIMUM_CHARGE;
        }
        return MINIMUM_CHARGE_BY_CURRENCY.getOrDefault(currency, DEFAULT_MINIMUM_CHARGE);
    }

    /**
     * skillars-deferred-106 AC5: Transfer.create(net -> coach connected account) on session
     * completion. Deterministic key {@code transfer-{transferGroupId}} so a re-driven
     * COACH_PAYOUT_TRANSFER outbox row replays the original transfer, never a second (AC5.3).
     * StripeExceptions are pre-classified (AC8.1) into {@link CoachPayoutTransferException} carrying
     * the retryable-vs-HOLD decision for the handler.
     *
     * <p>fix/payment-payouts-disputes-webhooks:
     * <ul>
     *   <li>an account that cannot receive a transfer is a non-retryable {@link CoachPayoutTransferException}
     *       (HOLD), not a plain {@link PaymentGatewayException} the handler would re-drive forever;</li>
     *   <li>Stripe keeps an idempotency key for 24h only, so a transfer already made for this
     *       {@code transfer_group} (its DB commit failed, then kept failing past the key window) is
     *       adopted instead of made again. Listed on every attempt: the payout row's own attempt count
     *       rolls back with each failed attempt, so it cannot tell a retry apart.</li>
     * </ul>
     */
    @Override
    public String transferToCoach(UUID transferGroupId, UUID coachId, BigDecimal netAmount, String currency) {
        String coachStripeAccountId = resolveTransferDestination(coachId);
        String existing = findExistingTransfer(transferGroupId, coachStripeAccountId, toCents(netAmount));
        if (existing != null) {
            log.warn("Stripe transfer already exists for transferGroup={} coach={} — adopting transferId={} "
                + "instead of creating another", transferGroupId, coachId, existing);
            return existing;
        }
        TransferCreateParams params = TransferCreateParams.builder()
            .setAmount(toCents(netAmount))
            .setCurrency(currency == null ? resolveCurrency() : currency.toLowerCase(Locale.ROOT))
            .setDestination(coachStripeAccountId)
            .setTransferGroup(transferGroupId.toString())
            .putMetadata("referenceId", transferGroupId.toString())
            .putMetadata("coachId", coachId.toString())
            .build();
        try {
            String transferId = stripeClient.createTransfer(params, "transfer-" + transferGroupId).getId();
            log.info("Stripe transfer created: transferGroup={} coach={} net={} transferId={}",
                transferGroupId, coachId, netAmount, transferId);
            return transferId;
        } catch (StripeException e) {
            throw classifiedTransferFailure(transferGroupId, coachId, e);
        }
    }

    /**
     * A transfer already made for this group, not fully reversed; null if none.
     *
     * <p>fix/payment-payout-recovery: adopted only when it went to the current destination for the amount now
     * due. The key {@code transfer-{group}} does not depend on the amount, so after a lost response the payout
     * may have been cut (a dispute resolved between attempts) or the coach may have reconnected a different
     * account; adopting blindly recorded the cut net while the coach kept the full transfer, or (another
     * destination, key expired) made a second transfer. Either mismatch is a non-retryable HOLD with its
     * PAYOUT_HELD alert, for an operator to reconcile — never re-driven automatically.
     */
    private String findExistingTransfer(UUID transferGroupId, String destination, long expectedCents) {
        final List<Transfer> live;
        try {
            live = stripeClient.listTransfersByGroup(transferGroupId.toString()).stream()
                .filter(t -> !Boolean.TRUE.equals(t.getReversed()))
                .toList();
        } catch (StripeException e) {
            // Unknown whether one exists: do not create blind — retry (the key still dedupes inside 24h).
            throw classifiedTransferFailure(transferGroupId, null, e);
        }
        if (live.isEmpty()) {
            return null;
        }
        Transfer existing = live.stream().filter(t -> destination.equals(t.getDestination())).findFirst().orElse(null);
        if (existing == null) {
            Transfer elsewhere = live.get(0);
            log.error("[COACH_PAYOUT_TRANSFER_MISMATCH] transferGroup={} already has transfer {} to {} — the coach's "
                + "account is now {}; not creating another", transferGroupId, elsewhere.getId(),
                elsewhere.getDestination(), destination);
            throw new CoachPayoutTransferException("payment.coachTransferFailed", false, REASON_DESTINATION_MISMATCH,
                new IllegalStateException("existing transfer " + elsewhere.getId() + " to " + elsewhere.getDestination()));
        }
        if (existing.getAmount() != null && existing.getAmount() != expectedCents) {
            log.error("[COACH_PAYOUT_TRANSFER_MISMATCH] transferGroup={} existing transfer {} is {} cents, {} due — "
                + "not adopting", transferGroupId, existing.getId(), existing.getAmount(), expectedCents);
            throw new CoachPayoutTransferException("payment.coachTransferFailed", false, REASON_AMOUNT_MISMATCH,
                new IllegalStateException("existing transfer " + existing.getId() + " amount=" + existing.getAmount()
                    + " expected=" + expectedCents));
        }
        return existing.getId();
    }

    private CoachPayoutTransferException classifiedTransferFailure(UUID transferGroupId, UUID coachId, StripeException e) {
        StripeTransferErrorClassifier.Decision d = StripeTransferErrorClassifier.classify(e);
        log.error("Stripe transfer failed: transferGroup={} coach={} retryable={} reason={} error={}",
            transferGroupId, coachId, d.retryable(), d.reason(), e.getMessage());
        return new CoachPayoutTransferException("payment.coachTransferFailed", d.retryable(), d.reason(), e);
    }

    /**
     * fix/payment-payouts-disputes-webhooks: the connected account a transfer can go to. A transfer needs
     * the account's {@code transfers} capability active — not {@code charges_enabled}, which is about the
     * account taking its own charges. Before the first {@code account.updated} that reports the
     * capability ({@code transfers_capability} null), falls back to onboarding COMPLETE with payouts
     * enabled. Anything else is a non-retryable failure: HOLD until the coach fixes the account.
     */
    private String resolveTransferDestination(UUID coachId) {
        CoachStripeAccount account = coachStripeAccountRepository.findById(coachId).orElse(null);
        if (account == null || account.getStripeAccountId() == null) {
            throw new CoachPayoutTransferException("payment.coachStripeNotConfigured", false, "NO_ACCOUNT",
                new IllegalStateException("coach has no connected Stripe account"));
        }
        TransferReadiness readiness = transferReadiness(account);
        if (readiness == TransferReadiness.READY) {
            return account.getStripeAccountId();
        }
        IllegalStateException detail = new IllegalStateException("onboarding=" + account.getOnboardingStatus()
            + " transfers=" + account.getTransfersCapability() + " payoutsEnabled=" + account.isPayoutsEnabled());
        // fix/payment-payout-recovery: Stripe parks a Standard account in `transfers: pending` while it reviews
        // KYC documents, and an OAuth (re)connect starts with no status at all until the first account.updated
        // / fallback sync. Both resolve on their own within minutes to days: retry with the outbox backoff
        // instead of parking the payout in HOLD.
        boolean retryable = readiness == TransferReadiness.PENDING;
        throw new CoachPayoutTransferException("payment.coachStripeNotConfigured", retryable,
            retryable ? "ACCOUNT_PENDING" : "ACCOUNT_NOT_READY", detail);
    }

    /** Whether a transfer to the account can be made now, soon (Stripe is still deciding), or not without the coach. */
    enum TransferReadiness { READY, PENDING, NOT_READY }

    /**
     * fix/payment-payout-recovery: the single transfer-readiness rule, shared with
     * {@code CoachPayoutHoldRecoveryService}. READY: the {@code transfers} capability is {@code active}, or —
     * before any {@code account.updated} reported it — onboarding COMPLETE with payouts enabled. PENDING:
     * capability {@code pending}, or no capability yet on an account still in onboarding PENDING (a fresh or
     * re-connect). Anything else (inactive, RESTRICTED, disconnected) needs the coach: NOT_READY.
     */
    static TransferReadiness transferReadiness(CoachStripeAccount account) {
        if (account == null || account.getStripeAccountId() == null) {
            return TransferReadiness.NOT_READY;
        }
        String capability = account.getTransfersCapability();
        if (capability != null) {
            if ("active".equalsIgnoreCase(capability)) {
                return TransferReadiness.READY;
            }
            return "pending".equalsIgnoreCase(capability) ? TransferReadiness.PENDING : TransferReadiness.NOT_READY;
        }
        if ("COMPLETE".equals(account.getOnboardingStatus()) && account.isPayoutsEnabled()) {
            return TransferReadiness.READY;
        }
        return "PENDING".equals(account.getOnboardingStatus()) ? TransferReadiness.PENDING : TransferReadiness.NOT_READY;
    }

    /**
     * skillars-deferred-106 AC5 / AC8.3: reverse (part of) a released transfer — the post-payout
     * dispute path. fix/payment-payouts-disputes-webhooks: the caller's key names one reversal.
     */
    @Override
    public void reverseTransfer(String stripeTransferId, BigDecimal amount, String idempotencyKey) {
        TransferReversalCollectionCreateParams params = TransferReversalCollectionCreateParams.builder()
            .setAmount(toCents(amount))
            .putMetadata(REVERSAL_KEY_METADATA, idempotencyKey)
            .build();
        try {
            // fix/payment-payout-recovery: Stripe forgets the idempotency key after 24h. A reversal whose DB
            // commit kept failing for longer would be made a second time (the coach clawed back twice) — so
            // look for it on the transfer first, the way transferToCoach adopts an existing transfer.
            boolean exists = stripeClient.listTransferReversals(stripeTransferId).stream()
                .anyMatch(r -> r.getMetadata() != null && idempotencyKey.equals(r.getMetadata().get(REVERSAL_KEY_METADATA)));
            if (exists) {
                log.warn("Stripe transfer reversal already exists: transferId={} key={} — not reversing again",
                    stripeTransferId, idempotencyKey);
                return;
            }
            stripeClient.createTransferReversal(stripeTransferId, params, idempotencyKey);
            log.info("Stripe transfer reversal created: transferId={} amount={} key={}", stripeTransferId, amount,
                idempotencyKey);
        } catch (StripeException e) {
            // fix/payment-payout-recovery: reversal-specific classification — balance_insufficient here means the
            // COACH's balance is gone (it never tops up by itself), not the platform's.
            StripeTransferErrorClassifier.Decision d = StripeTransferErrorClassifier.classifyReversal(e);
            log.error("Stripe transfer reversal failed: transferId={} retryable={} reason={} error={}",
                stripeTransferId, d.retryable(), d.reason(), e.getMessage());
            throw new CoachPayoutTransferException("payment.coachTransferReversalFailed", d.retryable(), d.reason(), e);
        }
    }

    @Override
    public void refund(String stripePaymentIntentId, BigDecimal amount, String idempotencyKey) {
        RefundCreateParams params = RefundCreateParams.builder()
            .setPaymentIntent(stripePaymentIntentId)
            .setAmount(toCents(amount))
            .build();
        try {
            // skillars-deferred-99 AC1: the key is chosen by the caller and names one logical refund,
            // so a re-attempt of that refund replays it at Stripe instead of double-crediting. It must
            // NOT be derived from the PaymentIntent alone: two distinct cash-outs against the same
            // PaymentIntent would then share a key and the second would be a silent replay.
            stripeClient.createRefund(params, idempotencyKey);
            log.info("Stripe refund issued: intentId={} amount={} key={}", stripePaymentIntentId, amount, idempotencyKey);
        } catch (StripeException e) {
            // A 4xx means Stripe rejected the request and created no refund. No status (network
            // failure / timeout) or a 5xx means the refund MAY have been executed: the caller must
            // not treat that as a failure and restore credit — it retries under the same key
            // (Stripe replays an executed refund) or leaves it for reconciliation.
            // ApiConnectionException carries status 0 (no HTTP response at all).
            // feat/coach-payouts-pack-and-credit: an idempotency conflict (409 — another request with
            // the same key still executing, or IdempotencyException) is NOT a rejection either: that
            // other request may be refunding right now, so restoring credit on it could pay twice.
            Integer status = e.getStatusCode();
            boolean outcomeUnknown = e instanceof ApiConnectionException || e instanceof IdempotencyException
                || status == null || status == 0 || status == 409 || status >= 500;
            log.error("Stripe refund {}: intentId={} key={} status={} error={}",
                outcomeUnknown ? "outcome unknown" : "rejected", stripePaymentIntentId, idempotencyKey,
                status, e.getMessage());
            throw new PaymentGatewayException(outcomeUnknown ? "payment.refundOutcomeUnknown" : "payment.refundFailed");
        }
    }

    @Override
    public BigDecimal getRefundableAmount(String stripePaymentIntentId) {
        try {
            Charge charge = stripeClient.retrievePaymentIntentWithLatestCharge(stripePaymentIntentId)
                .getLatestChargeObject();
            if (charge == null) {
                return BigDecimal.ZERO;
            }
            long captured = charge.getAmountCaptured() == null ? 0L : charge.getAmountCaptured();
            long refunded = charge.getAmountRefunded() == null ? 0L : charge.getAmountRefunded();
            return BigDecimal.valueOf(Math.max(0L, captured - refunded), 2);
        } catch (StripeException e) {
            // Nothing has been debited or refunded yet — a distinct code, so the payer is not told a
            // refund failed.
            log.error("Stripe refundable-amount lookup failed: intentId={} error={}", stripePaymentIntentId, e.getMessage());
            // fix/audit3-wallet-cashout-integrity: a lookup that can never succeed (unknown / invalid PaymentIntent,
            // no permission on it) gets its own code, so a cash-out can skip that charge instead of failing on it
            // forever; anything that may pass on a retry stays payment.refundLookupFailed.
            throw new PaymentGatewayException(isPermanentLookupFailure(e)
                ? PaymentGateway.REFUND_TARGET_INVALID : "payment.refundLookupFailed");
        }
    }

    /**
     * fix/audit3-wallet-cashout-integrity: true for a Stripe error no retry will fix — a 4xx bad request (e.g. 404
     * {@code resource_missing}) or a permission error on the object. Network errors, 429 / rate limits
     * ({@code RateLimitException} extends {@code InvalidRequestException}, so checked first), 5xx and anything
     * unmapped are transient (fail safe).
     */
    static boolean isPermanentLookupFailure(StripeException e) {
        Integer status = e.getStatusCode();
        if (e instanceof ApiConnectionException || e instanceof com.stripe.exception.RateLimitException
                || (status != null && (status == 429 || status >= 500))) {
            return false;
        }
        return e instanceof com.stripe.exception.InvalidRequestException
            || e instanceof com.stripe.exception.PermissionException;
    }

    @Override
    public void freezePayment(String paymentIntentId) {
        // Freeze is a no-op at Stripe level — funds remain captured; admin resolves manually.
        log.info("Payment frozen (dispute hold): intentId={}", paymentIntentId);
    }

    @Override
    public String createSetupIntent(String stripeCustomerId) {
        SetupIntentCreateParams params = SetupIntentCreateParams.builder()
            .setCustomer(stripeCustomerId)
            .addPaymentMethodType("card")
            .build();
        try {
            return stripeClient.createSetupIntent(params).getClientSecret();
        } catch (StripeException e) {
            log.error("Stripe SetupIntent creation failed: customerId={} error={}", stripeCustomerId, e.getMessage());
            throw new PaymentGatewayException("payment.setupIntentFailed");
        }
    }

    @Override
    public boolean isCoachPaymentReady(UUID coachId) {
        return coachStripeAccountRepository.findById(coachId)
            .map(a -> "COMPLETE".equals(a.getOnboardingStatus()) && a.isChargesEnabled())
            .orElse(false);
    }

    @Override
    public String createStripeCustomer(Long parentId) {
        CustomerCreateParams params = CustomerCreateParams.builder()
            .putMetadata("userId", parentId.toString())
            .build();
        try {
            return stripeClient.createCustomer(params).getId();
        } catch (StripeException e) {
            log.error("Stripe customer creation failed: parentId={} error={}", parentId, e.getMessage());
            throw new PaymentGatewayException("payment.customerCreationFailed");
        }
    }

    @Override
    @Deprecated
    public String capturePayment(UUID referenceId, UUID coachId, BigDecimal amount) {
        log.warn("capturePayment() is deprecated — use chargeAndCapture(). referenceId={}", referenceId);
        return chargeAndCapture(referenceId, null, coachId, amount);
    }

    private String resolveCoachStripeAccountId(UUID coachId) {
        return coachStripeAccountRepository.findById(coachId)
            .filter(a -> "COMPLETE".equals(a.getOnboardingStatus()) && a.isChargesEnabled())
            .map(CoachStripeAccount::getStripeAccountId)
            .orElseThrow(() -> new PaymentGatewayException("payment.coachStripeNotConfigured"));
    }

    private long toCents(BigDecimal amount) {
        return amount.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /**
     * skillars-deferred-90 AC6: validate {@code platform.payment.currency} as an ISO-4217 code
     * before it reaches Stripe. {@code configService.getString} throws {@link IllegalStateException}
     * for a missing key (handled by the caller's catch); {@link Currency#getInstance} throws
     * {@link IllegalArgumentException} for a blank / unknown code and {@link NullPointerException}
     * for {@code null} — and {@code IllegalArgumentException} is the SUPERTYPE of
     * {@code NumberFormatException}, so the caller's {@code catch (IllegalStateException |
     * NumberFormatException)} would NOT catch it. Map both to the same
     * {@code payment.configurationUnavailable} failure the caller already raises.
     * Stripe wants the lower-cased code.
     */
    private String resolveCurrency() {
        final String raw = configService.getString("platform.payment.currency");
        try {
            final Currency currency = Currency.getInstance(
                raw == null ? null : raw.strip().toUpperCase(Locale.ROOT));
            return currency.getCurrencyCode().toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException | NullPointerException e) {
            log.error("Payment configuration invalid: platform.payment.currency='{}' is not a valid ISO-4217 code", raw);
            throw new PaymentGatewayException("payment.configurationUnavailable", e);
        }
    }
}
