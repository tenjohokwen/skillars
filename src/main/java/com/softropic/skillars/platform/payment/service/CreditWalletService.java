package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.payment.contract.CreditLedgerEntryType;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.ParentCreditLedger;
import com.softropic.skillars.platform.payment.repo.ParentCreditLedgerRepository;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CreditWalletService {

    private final ParentCreditLedgerRepository ledgerRepository;
    private final StripeCustomerRepository stripeCustomerRepository;
    private final PessimisticLockRetryer lockRetryer;

    public BigDecimal getBalance(Long parentId) {
        return ledgerRepository.sumByParentId(parentId).orElse(BigDecimal.ZERO);
    }

    /**
     * fix/payment-money-integrity: the cash-out's balance check and its two debit rows, atomic per
     * payer. The ledger is append-only (no balance row to lock), so the payer's
     * {@code stripe_customers} row is the lock: two concurrent cash-outs used to read the balance
     * in one transaction and write the debits in others, both passed the check, and the wallet went
     * negative. Under the row lock the second caller re-reads the balance only after the first has
     * committed its debits (READ COMMITTED sees them), so it fails with
     * {@code payment.insufficientCredit} instead of overdrawing.
     *
     * <p>The two rows add up to exactly {@code netAmount + feeAmount} = the amount the payer asked
     * to cash out — {@code CASH_OUT_DEBIT} is what goes back to the card, {@code STRIPE_FEE_DEBIT}
     * the processing fee. Both carry {@code cashOutId} as {@code reference_id} so a reversal and the
     * Stripe idempotency key ({@code cashout-{cashOutId}}) tie back to them.
     *
     * <p>Scope: this serializes cash-outs against each other. The booking-settlement credit
     * deductions ({@code PaymentLifecycleService}) read the balance outside this lock and are not
     * serialized against a cash-out — see the PR design note.
     */
    @Transactional
    public void debitForCashOut(Long parentId, UUID cashOutId, BigDecimal netAmount, BigDecimal feeAmount) {
        lockRetryer.withBoundedRetry("CreditWalletService.debitForCashOut",
            () -> stripeCustomerRepository.findByIdForUpdate(parentId)
                .orElseThrow(() -> new PaymentGatewayException("payment.noPaymentMethod")));

        BigDecimal requested = netAmount.add(feeAmount);
        BigDecimal balance = ledgerRepository.sumByParentId(parentId).orElse(BigDecimal.ZERO);
        if (balance.compareTo(requested) < 0) {
            throw new PaymentGatewayException("payment.insufficientCredit");
        }
        writeLedgerEntry(parentId, netAmount.negate(), CreditLedgerEntryType.CASH_OUT_DEBIT, cashOutId,
            "Credit cash-out");
        if (feeAmount.signum() > 0) {
            writeLedgerEntry(parentId, feeAmount.negate(), CreditLedgerEntryType.STRIPE_FEE_DEBIT, cashOutId,
                "Stripe processing fee for cash-out");
        }
    }

    /**
     * Compensation for a cash-out whose Stripe refund failed: restores the debited net and fee as
     * two {@code CASH_OUT_REVERSAL} rows tagged with the same {@code cashOutId}, in ONE transaction —
     * two separate {@code writeLedgerEntry} calls could commit the net and lose the fee.
     */
    @Transactional
    public void reverseCashOut(Long parentId, UUID cashOutId, BigDecimal netAmount, BigDecimal feeAmount) {
        writeLedgerEntry(parentId, netAmount, CreditLedgerEntryType.CASH_OUT_REVERSAL, cashOutId,
            "Cashout refund failed - credit restored");
        if (feeAmount.signum() > 0) {
            writeLedgerEntry(parentId, feeAmount, CreditLedgerEntryType.CASH_OUT_REVERSAL, cashOutId,
                "Cashout refund failed - fee restored");
        }
    }

    @Transactional
    public void writeLedgerEntry(Long parentId, BigDecimal amount, String type,
                                  UUID referenceId, String description) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalArgumentException("Ledger entry amount must be non-zero");
        }
        ParentCreditLedger entry = new ParentCreditLedger();
        entry.setParentId(parentId);
        entry.setAmount(amount);
        entry.setType(type);
        entry.setReferenceId(referenceId);
        entry.setDescription(description);
        ledgerRepository.save(entry);
        log.debug("Credit ledger entry written: parentId={} type={} amount={}", parentId, type, amount);
    }
}
