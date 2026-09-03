package com.softropic.skillars.platform.payment.service;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.SetupIntentCreateParams;
import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
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

    @Override
    public String chargeAndCapture(UUID referenceId, Long parentId, UUID coachId, BigDecimal amount) {
        String coachStripeAccountId = resolveCoachStripeAccountId(coachId);

        BigDecimal commissionRate;
        String currency;
        try {
            commissionRate = new BigDecimal(configService.getString("platform.commission.rate"));
            currency = resolveCurrency();
        } catch (IllegalStateException | NumberFormatException e) {
            log.error("Payment configuration unavailable: error={}", e.getMessage());
            throw new PaymentGatewayException("payment.configurationUnavailable", e);
        }
        long amountCents = toCents(amount);
        long feeCents = toCents(amount.multiply(commissionRate).setScale(2, RoundingMode.HALF_UP));

        PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
            .setAmount(amountCents)
            .setCurrency(currency)
            .setConfirm(true)
            .setTransferData(PaymentIntentCreateParams.TransferData.builder()
                .setDestination(coachStripeAccountId)
                .build())
            .setApplicationFeeAmount(feeCents)
            .putMetadata("referenceId", referenceId != null ? referenceId.toString() : "")
            .putMetadata("coachId", coachId.toString());

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
            log.info("Stripe charge captured: referenceId={} intentId={}", referenceId, intent.getId());
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

    @Override
    public void refund(String stripePaymentIntentId, BigDecimal netAmount) {
        RefundCreateParams params = RefundCreateParams.builder()
            .setPaymentIntent(stripePaymentIntentId)
            .setAmount(toCents(netAmount))
            .build();
        try {
            stripeClient.createRefund(params);
            log.info("Stripe refund issued: intentId={} amount={}", stripePaymentIntentId, netAmount);
        } catch (StripeException e) {
            log.error("Stripe refund failed: intentId={} error={}", stripePaymentIntentId, e.getMessage());
            throw new PaymentGatewayException("payment.refundFailed");
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
