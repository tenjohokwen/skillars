package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.ParentCreditLedger;
import com.softropic.skillars.platform.payment.repo.ParentCreditLedgerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CreditWalletService {

    private final ParentCreditLedgerRepository ledgerRepository;

    public BigDecimal getBalance(Long parentId) {
        return ledgerRepository.sumByParentId(parentId).orElse(BigDecimal.ZERO);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void debitForCashOut(Long parentId, BigDecimal requestedAmount, BigDecimal feeAmount) {
        BigDecimal balance = ledgerRepository.sumByParentId(parentId).orElse(BigDecimal.ZERO);
        if (balance.compareTo(requestedAmount) < 0) {
            throw new PaymentGatewayException("payment.insufficientCredit");
        }
        writeLedgerEntry(parentId, requestedAmount.negate(), "CASH_OUT_DEBIT", null, "Credit cash-out");
        writeLedgerEntry(parentId, feeAmount.negate(), "STRIPE_FEE_DEBIT", null, "Stripe processing fee for cash-out");
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
