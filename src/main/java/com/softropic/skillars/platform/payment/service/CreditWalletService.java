package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.payment.contract.CreditLedgerEntryType;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.CashOutReconciliation;
import com.softropic.skillars.platform.payment.repo.CashOutReconciliationRepository;
import com.softropic.skillars.platform.payment.repo.ChargeDisputeRepository;
import com.softropic.skillars.platform.payment.repo.ParentCreditLedger;
import com.softropic.skillars.platform.payment.repo.ParentCreditLedgerRepository;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
    private final CashOutReconciliationRepository cashOutReconciliationRepository;
    private final ChargeDisputeRepository chargeDisputeRepository;

    public BigDecimal getBalance(Long parentId) {
        return ledgerRepository.sumByParentId(parentId).orElse(BigDecimal.ZERO);
    }

    /**
     * fix/payment-wallet-dispute-integrity: the wallet split into its two pools. {@code total} is everything
     * (what bookings can spend); {@code spendOnly} is credit that may be spent on bookings but never cashed
     * out (an expired-pack parent-cancel refund — owner decision 2026-10-29); {@code cashable} is the rest.
     */
    public record WalletBalance(BigDecimal total, BigDecimal spendOnly, BigDecimal cashable) {
    }

    /**
     * The payer's wallet in its two pools: the spend-only pool is the sum of every row's
     * {@code spend_only_amount} (V208), clamped to {@code [0, total]} so an edge case can never report more
     * spend-only credit than the wallet holds; cashable is {@code total - spendOnly}.
     */
    public WalletBalance getWalletBalance(Long parentId) {
        BigDecimal total = getBalance(parentId);
        BigDecimal spendOnly = ledgerRepository.sumSpendOnlyByParentId(parentId);
        if (spendOnly == null) {
            spendOnly = BigDecimal.ZERO;
        }
        spendOnly = spendOnly.max(BigDecimal.ZERO).min(total.max(BigDecimal.ZERO));
        return new WalletBalance(total, spendOnly, total.subtract(spendOnly));
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
     * <p>Scope: this serializes cash-outs against each other, and — through {@link #lockAndGetBalance}
     * — against the booking-settlement credit deductions in {@code PaymentLifecycleService}.
     *
     * <p>feat/coach-payouts-pack-and-credit (C3 item 4): also writes the PENDING
     * {@code cash_out_reconciliations} record in this same transaction, so a debit can never exist
     * without the durable record that tracks its refund.
     */
    @Transactional
    public void debitForCashOut(Long parentId, UUID cashOutId, BigDecimal netAmount, BigDecimal feeAmount,
                                String paymentIntentId) {
        lockRetryer.withBoundedRetry("CreditWalletService.debitForCashOut",
            () -> stripeCustomerRepository.findByIdForUpdate(parentId)
                .orElseThrow(() -> new PaymentGatewayException("payment.noPaymentMethod")));

        // fix/payment-dispute-credit-trace-and-lost-dispute: while a card dispute is open on one of the
        // payer's charges, the dispute may still take wallet credit back (credit funded by that charge
        // written after the dispute opened is reversed when it is lost), so nothing leaves as cash. Read
        // under the wallet lock, which the dispute webhook's reversal also takes.
        if (chargeDisputeRepository.existsOpenDisputeForPayer(parentId)) {
            throw new PaymentGatewayException("payment.cashOutBlockedByDispute");
        }
        // fix/payment-wallet-dispute-integrity: so is an open in-app dispute on one of the payer's bookings —
        // an UPHOLD_COACH resolution claws the refund back from the wallet, capped at the balance, so credit
        // cashed out meanwhile would be paid twice (to the card, and to the coach in full).
        if (chargeDisputeRepository.existsOpenAdminDisputeForPayer(parentId)) {
            throw new PaymentGatewayException("payment.cashOutBlockedByDispute");
        }
        BigDecimal requested = netAmount.add(feeAmount);
        // Only the cashable pool leaves as cash; spend-only credit stays for bookings.
        WalletBalance balance = getWalletBalance(parentId);
        if (balance.total().compareTo(requested) < 0) {
            throw new PaymentGatewayException("payment.insufficientCredit");
        }
        if (balance.cashable().compareTo(requested) < 0) {
            throw new PaymentGatewayException("payment.cashOutExceedsCashable");
        }
        writeLedgerEntry(parentId, netAmount.negate(), CreditLedgerEntryType.CASH_OUT_DEBIT, cashOutId,
            "Credit cash-out");
        if (feeAmount.signum() > 0) {
            writeLedgerEntry(parentId, feeAmount.negate(), CreditLedgerEntryType.STRIPE_FEE_DEBIT, cashOutId,
                "Stripe processing fee for cash-out");
        }
        cashOutReconciliationRepository.save(
            CashOutReconciliation.pending(cashOutId, parentId, paymentIntentId, netAmount, feeAmount));
    }

    /**
     * feat/coach-payouts-pack-and-credit (C3 item 3, the #318 follow-up): the booking-settlement side of
     * the per-payer wallet lock. Takes the same {@code stripe_customers} row lock as
     * {@link #debitForCashOut}, but WAITS for it instead of NOWAIT + retry (see
     * {@code StripeCustomerRepository.findByIdForUpdateWaiting} for why), and returns the balance read
     * UNDER it, so a settlement's credit split and its {@code BOOKING_DEDUCTION} — written later in the
     * caller's transaction, while the lock is still held — cannot overdraw the wallet against a
     * concurrent cash-out or a concurrent settlement for the same payer. The lock lasts until the
     * caller's transaction ends.
     *
     * <p>A payer with no {@code stripe_customers} row cannot cash out (the refund needs that row's
     * PaymentIntent), so there is nothing to serialize against and the balance is returned unlocked.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal lockAndGetBalance(Long parentId) {
        boolean locked = stripeCustomerRepository.findByIdForUpdateWaiting(parentId).isPresent();
        if (!locked) {
            log.debug("No stripe_customers row for parentId={} — wallet balance read without the payer lock", parentId);
        }
        return getBalance(parentId);
    }

    /**
     * feat/payment-forfeit-payouts-and-clawback: takes back up to {@code refundAmount} of a booking's refund
     * from the payer's wallet — an {@code UPHOLD_COACH} dispute resolution. Capped at the balance read under
     * the per-payer wallet lock ({@link #lockAndGetBalance}), so the wallet never goes below zero and a
     * concurrent cash-out or settlement cannot spend the same credit. Nothing is written when the wallet is
     * empty.
     *
     * @return what was actually taken back (zero to {@code refundAmount})
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal debitRefundReversal(Long parentId, UUID bookingId, BigDecimal refundAmount) {
        return debitRefundReversal(parentId, bookingId, refundAmount, false);
    }

    /**
     * As {@link #debitRefundReversal(Long, UUID, BigDecimal)}; fix/payment-wallet-dispute-integrity: takes
     * from the pool the refund went into first — the spend-only pool for a spend-only refund
     * ({@code spendOnlyRefund}), else the cashable pool — and from the other pool for the rest.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal debitRefundReversal(Long parentId, UUID bookingId, BigDecimal refundAmount,
                                          boolean spendOnlyRefund) {
        lockAndGetBalance(parentId);
        WalletBalance balance = getWalletBalance(parentId);
        BigDecimal recovered = refundAmount.min(balance.total().max(BigDecimal.ZERO));
        if (recovered.signum() <= 0) {
            log.info("Refund reversal for booking {}: payer {} has no wallet balance — nothing recovered", bookingId, parentId);
            return BigDecimal.ZERO;
        }
        writeLedgerEntry(parentId, recovered.negate(), CreditLedgerEntryType.BOOKING_REFUND_REVERSAL, bookingId,
            "Dispute resolved in the coach's favour — refund reversed", null, null,
            spendOnlyPart(balance, recovered, spendOnlyRefund).negate());
        return recovered;
    }

    /**
     * fix/payment-wallet-dispute-integrity: a booking settlement's wallet debit ({@code BOOKING_DEDUCTION}).
     * Spends consume the spend-only pool first, so spend-only credit is used up before cashable credit. The
     * caller holds the payer's wallet lock ({@link #lockAndGetBalance}) whenever there is credit to spend, so
     * the split is read under it.
     */
    @Transactional
    public void debitForBooking(Long parentId, BigDecimal amount, UUID referenceId, String description) {
        WalletBalance balance = getWalletBalance(parentId);
        writeLedgerEntry(parentId, amount.negate(), CreditLedgerEntryType.BOOKING_DEDUCTION, referenceId,
            description, null, null, spendOnlyPart(balance, amount, true).negate());
    }

    /**
     * fix/payment-wallet-dispute-integrity: a failed settlement's {@code BOOKING_DEDUCTION} given back
     * ({@code BOOKING_DEDUCTION_REVERSAL}) into the pools it came from — spend-only up to what the deduction(s)
     * under {@code referenceId} took from that pool and earlier restores have not yet returned, the rest
     * cashable.
     */
    @Transactional
    public void restoreBookingDeduction(Long parentId, BigDecimal amount, UUID referenceId, String description) {
        BigDecimal deductedSpendOnly = ledgerRepository.sumSpendOnlyByParentIdAndReferenceIdAndType(
            parentId, referenceId, CreditLedgerEntryType.BOOKING_DEDUCTION).negate();
        BigDecimal restoredSpendOnly = ledgerRepository.sumSpendOnlyByParentIdAndReferenceIdAndType(
            parentId, referenceId, CreditLedgerEntryType.BOOKING_DEDUCTION_REVERSAL);
        BigDecimal spendOnly = deductedSpendOnly.subtract(restoredSpendOnly).max(BigDecimal.ZERO).min(amount);
        writeLedgerEntry(parentId, amount, CreditLedgerEntryType.BOOKING_DEDUCTION_REVERSAL, referenceId,
            description, null, null, spendOnly);
    }

    /**
     * How much of a debit of {@code take} comes out of the spend-only pool: all it can when
     * {@code spendOnlyFirst}, else only what the cashable pool cannot cover. Never more than the pool holds.
     */
    static BigDecimal spendOnlyPart(WalletBalance balance, BigDecimal take, boolean spendOnlyFirst) {
        BigDecimal pool = balance.spendOnly().max(BigDecimal.ZERO);
        BigDecimal part = spendOnlyFirst
            ? take.min(pool)
            : take.subtract(balance.cashable().max(BigDecimal.ZERO)).max(BigDecimal.ZERO).min(pool);
        return part.max(BigDecimal.ZERO);
    }

    /**
     * fix/payment-dispute-credit-trace-and-lost-dispute: takes back up to {@code amount} of wallet credit a
     * now-disputed card charge had funded, as a {@code CHARGEBACK_CREDIT_REVERSAL} under {@code reference}.
     * Capped at the balance read under the per-payer wallet lock — never below zero. A no-op when this
     * payer already has a reversal under {@code reference} (V199 {@code uq_pcl_chargeback_credit_reversal}
     * backstops it).
     *
     * @return what was actually taken back (zero to {@code amount})
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal debitChargebackCredit(Long parentId, UUID reference, BigDecimal amount, String description) {
        if (amount == null || amount.signum() <= 0
                || ledgerRepository.existsByReferenceIdAndParentIdAndType(reference, parentId,
                    CreditLedgerEntryType.CHARGEBACK_CREDIT_REVERSAL)) {
            return BigDecimal.ZERO;
        }
        lockAndGetBalance(parentId);
        WalletBalance balance = getWalletBalance(parentId);
        BigDecimal recovered = amount.min(balance.total().max(BigDecimal.ZERO));
        if (recovered.signum() <= 0) {
            log.info("Chargeback credit reversal {}: payer {} has no wallet balance — nothing recovered", reference, parentId);
            return BigDecimal.ZERO;
        }
        // fix/payment-wallet-dispute-integrity: cashable credit first — the bank has already returned the money
        // to the card, so what could otherwise leave as cash goes first; a won dispute's restore mirrors it.
        writeLedgerEntry(parentId, recovered.negate(), CreditLedgerEntryType.CHARGEBACK_CREDIT_REVERSAL, reference,
            description, null, null, spendOnlyPart(balance, recovered, false).negate());
        return recovered;
    }

    /**
     * The card dispute was won: gives back what {@link #debitChargebackCredit} took under {@code reference},
     * as one {@code CHARGEBACK_CREDIT_RESTORE}. A no-op when it was already restored (V199
     * {@code uq_pcl_chargeback_credit_restore}).
     *
     * @return true when a restore row was written
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean restoreChargebackCredit(Long parentId, UUID reference, BigDecimal amount) {
        return restoreChargebackCredit(parentId, reference, amount, BigDecimal.ZERO);
    }

    /**
     * As {@link #restoreChargebackCredit(Long, UUID, BigDecimal)}, giving {@code spendOnlyAmount} of it back to
     * the spend-only pool — the part the reversal took from that pool (fix/payment-wallet-dispute-integrity).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean restoreChargebackCredit(Long parentId, UUID reference, BigDecimal amount,
                                           BigDecimal spendOnlyAmount) {
        if (amount == null || amount.signum() <= 0
                || ledgerRepository.existsByReferenceIdAndParentIdAndType(reference, parentId,
                    CreditLedgerEntryType.CHARGEBACK_CREDIT_RESTORE)) {
            return false;
        }
        BigDecimal spendOnly = spendOnlyAmount == null ? BigDecimal.ZERO
            : spendOnlyAmount.max(BigDecimal.ZERO).min(amount);
        writeLedgerEntry(parentId, amount, CreditLedgerEntryType.CHARGEBACK_CREDIT_RESTORE, reference,
            "Card dispute resolved in Skillars' favour — credit restored", null, null, spendOnly);
        return true;
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
        writeLedgerEntry(parentId, amount, type, referenceId, description, null, null);
    }

    /**
     * fix/payment-dispute-credit-trace-and-lost-dispute: as {@link #writeLedgerEntry(Long, BigDecimal, String,
     * UUID, String)}, also recording the card charge a refund credit came from and its card part (V199), for
     * a refund whose booking's own {@code booking_payments} row does not name that charge.
     */
    @Transactional
    public void writeLedgerEntry(Long parentId, BigDecimal amount, String type, UUID referenceId,
                                 String description, String sourcePaymentIntentId, BigDecimal sourceCardAmount) {
        writeLedgerEntry(parentId, amount, type, referenceId, description, sourcePaymentIntentId, sourceCardAmount,
            BigDecimal.ZERO);
    }

    /**
     * fix/payment-wallet-dispute-integrity: as above, with the part of {@code amount} that belongs to the
     * spend-only pool (V208 {@code spend_only_amount}: same sign as {@code amount}, never larger; zero = all
     * cashable).
     */
    @Transactional
    public void writeLedgerEntry(Long parentId, BigDecimal amount, String type, UUID referenceId,
                                 String description, String sourcePaymentIntentId, BigDecimal sourceCardAmount,
                                 BigDecimal spendOnlyAmount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalArgumentException("Ledger entry amount must be non-zero");
        }
        BigDecimal spendOnly = spendOnlyAmount == null ? BigDecimal.ZERO : spendOnlyAmount;
        if (spendOnly.signum() != 0
                && (spendOnly.signum() != amount.signum() || spendOnly.abs().compareTo(amount.abs()) > 0)) {
            throw new IllegalArgumentException("Spend-only part must have the amount's sign and not exceed it");
        }
        ParentCreditLedger entry = new ParentCreditLedger();
        entry.setParentId(parentId);
        entry.setAmount(amount);
        entry.setType(type);
        entry.setReferenceId(referenceId);
        entry.setDescription(description);
        entry.setSourcePaymentIntentId(sourcePaymentIntentId);
        entry.setSourceCardAmount(sourceCardAmount);
        entry.setSpendOnlyAmount(spendOnly);
        ledgerRepository.save(entry);
        log.debug("Credit ledger entry written: parentId={} type={} amount={}", parentId, type, amount);
    }
}
