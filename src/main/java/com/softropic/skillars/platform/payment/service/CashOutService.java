package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
@Slf4j
public class CashOutService {

    private final CreditWalletService creditWalletService;
    private final StripeCustomerRepository stripeCustomerRepository;
    private final PaymentGateway paymentGateway;
    private final ConfigService configService;

    public void processCashOut(Long parentId, BigDecimal requestedAmount) {
        BigDecimal balance = creditWalletService.getBalance(parentId);
        if (balance.compareTo(requestedAmount) < 0) {
            throw new PaymentGatewayException("payment.insufficientCredit");
        }

        String paymentIntentId = stripeCustomerRepository.findById(parentId)
            .map(sc -> sc.getLastPaymentIntentId())
            .orElseThrow(() -> new PaymentGatewayException("payment.noPaymentMethod"));
        if (paymentIntentId == null) {
            throw new PaymentGatewayException("payment.noPaymentMethod");
        }

        BigDecimal feeRate = new BigDecimal(configService.getString("payment.stripe.feeRate"));
        BigDecimal feeFixed = new BigDecimal(configService.getString("payment.stripe.feeFixed"));
        BigDecimal feeAmount = requestedAmount.multiply(feeRate).add(feeFixed).setScale(2, RoundingMode.HALF_UP);
        BigDecimal netAmount = requestedAmount.subtract(feeAmount);

        if (netAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new PaymentGatewayException("payment.cashOutTooSmall");
        }

        creditWalletService.writeLedgerEntry(parentId, requestedAmount.negate(),
            "CASH_OUT_DEBIT", null, "Credit cash-out");
        creditWalletService.writeLedgerEntry(parentId, feeAmount.negate(),
            "STRIPE_FEE_DEBIT", null, "Stripe processing fee for cash-out");

        try {
            paymentGateway.refund(paymentIntentId, netAmount);
        } catch (PaymentGatewayException e) {
            log.error("Cash-out refund failed for parentId={}, compensating credit", parentId);
            creditWalletService.writeLedgerEntry(parentId, requestedAmount,
                "CASH_OUT_REVERSAL", null, "Cashout refund failed - credit restored");
            creditWalletService.writeLedgerEntry(parentId, feeAmount,
                "CASH_OUT_REVERSAL", null, "Cashout refund failed - fee restored");
            throw new PaymentGatewayException("payment.refundFailed");
        }
    }
}
