package com.softropic.skillars.platform.payment.service;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.SetupIntentCreateParams;
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
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class StripePaymentGateway implements PaymentGateway {

    private final CoachStripeAccountRepository coachStripeAccountRepository;
    private final StripeCustomerRepository stripeCustomerRepository;
    private final ConfigService configService;
    private final StripeClient stripeClient;

    @Override
    public String chargeAndCapture(UUID referenceId, Long parentId, UUID coachId, BigDecimal amount) {
        String coachStripeAccountId = resolveCoachStripeAccountId(coachId);
        BigDecimal commissionRate = new BigDecimal(configService.getString("platform.commission.rate"));
        long amountCents = toCents(amount);
        long feeCents = toCents(amount.multiply(commissionRate).setScale(2, RoundingMode.HALF_UP));

        PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
            .setAmount(amountCents)
            .setCurrency("eur")
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

        try {
            PaymentIntent intent = stripeClient.createPaymentIntent(builder.build());
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
            .putMetadata("parentId", parentId.toString())
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
}
