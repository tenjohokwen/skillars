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
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
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
import java.util.Locale;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class StripePaymentGateway implements PaymentGateway {

    private static final Duration IDEMPOTENCY_KEY_WINDOW = Duration.ofMinutes(1);

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

        // skillars-deferred-106 AC1.3: the idempotency-key scheme is preserved UNCHANGED — only the
        // PaymentIntent params changed.
        //
        // Key incorporates parentId, not just referenceId: SessionPackPaymentService.purchasePack
        // passes a shared session-pack tier id as referenceId, which repeats across every parent
        // who buys that tier — referenceId alone would let two different parents' purchases
        // collide on the same Stripe idempotency key.
        //
        // The time bucket exists for the same purchasePack path: referenceId there is stable
        // per tier, not per purchase attempt, so a parent legitimately buying the same tier
        // twice would otherwise collide on the same key as their first purchase and Stripe
        // would silently replay the first PaymentIntent instead of charging again. A short
        // window still dedupes true retries (e.g. a duplicate event/network resend of the same
        // attempt) without blocking a second, later, genuinely separate purchase. The two
        // PaymentLifecycleService callers pass a booking/batch id that is already unique per
        // charge attempt, so the bucket is a no-op there — those are fired once per booking
        // acceptance with no scheduled retry of the charge itself.
        long bucket = Instant.now(ClockProvider.getClock()).getEpochSecond() / IDEMPOTENCY_KEY_WINDOW.toSeconds();
        // parentId is null only via the deprecated capturePayment() overload (no current caller).
        // Fall back to coachId — always non-null — rather than the literal "null", so two
        // distinct null-parentId charges sharing a referenceId don't collide on one key.
        Object keyOwner = parentId != null ? parentId : coachId;
        String idempotencyKey = "pi-" + referenceId + "-" + keyOwner + "-" + bucket;
        try {
            PaymentIntent intent = stripeClient.createPaymentIntent(builder.build(), idempotencyKey);
            if (stripeCustomer != null) {
                stripeCustomer.setLastPaymentIntentId(intent.getId());
                stripeCustomerRepository.save(stripeCustomer);
            }
            log.info("Stripe charge captured (platform account): referenceId={} intentId={}", referenceId, intent.getId());
            return intent.getId();
        } catch (StripeException e) {
            log.error("Stripe charge failed: referenceId={} error={}", referenceId, e.getMessage());
            throw new PaymentGatewayException("payment.lifecycleFailure");
        }
    }

    @Override
    public String chargeAndCaptureForBatch(UUID batchId, Long parentId, UUID coachId, BigDecimal amount) {
        return chargeAndCapture(batchId, parentId, coachId, amount);
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
        String existing = findExistingTransfer(transferGroupId, coachStripeAccountId);
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

    /** A transfer already made for this group to this destination, not fully reversed; null if none. */
    private String findExistingTransfer(UUID transferGroupId, String destination) {
        try {
            return stripeClient.listTransfersByGroup(transferGroupId.toString()).stream()
                .filter(t -> destination.equals(t.getDestination()))
                .filter(t -> !Boolean.TRUE.equals(t.getReversed()))
                .map(Transfer::getId)
                .findFirst()
                .orElse(null);
        } catch (StripeException e) {
            // Unknown whether one exists: do not create blind — retry (the key still dedupes inside 24h).
            throw classifiedTransferFailure(transferGroupId, null, e);
        }
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
        String capability = account.getTransfersCapability();
        boolean usable = capability != null
            ? "active".equalsIgnoreCase(capability)
            : "COMPLETE".equals(account.getOnboardingStatus()) && account.isPayoutsEnabled();
        if (!usable) {
            throw new CoachPayoutTransferException("payment.coachStripeNotConfigured", false, "ACCOUNT_NOT_READY",
                new IllegalStateException("onboarding=" + account.getOnboardingStatus() + " transfers="
                    + capability + " payoutsEnabled=" + account.isPayoutsEnabled()));
        }
        return account.getStripeAccountId();
    }

    /**
     * skillars-deferred-106 AC5 / AC8.3: reverse (part of) a released transfer — the post-payout
     * dispute path. fix/payment-payouts-disputes-webhooks: the caller's key names one reversal.
     */
    @Override
    public void reverseTransfer(String stripeTransferId, BigDecimal amount, String idempotencyKey) {
        TransferReversalCollectionCreateParams params = TransferReversalCollectionCreateParams.builder()
            .setAmount(toCents(amount))
            .build();
        try {
            stripeClient.createTransferReversal(stripeTransferId, params, idempotencyKey);
            log.info("Stripe transfer reversal created: transferId={} amount={} key={}", stripeTransferId, amount,
                idempotencyKey);
        } catch (StripeException e) {
            StripeTransferErrorClassifier.Decision d = StripeTransferErrorClassifier.classify(e);
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
            throw new PaymentGatewayException("payment.refundLookupFailed");
        }
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
