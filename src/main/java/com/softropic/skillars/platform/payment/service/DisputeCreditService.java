package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.contract.CreditLedgerEntryType;
import com.softropic.skillars.platform.payment.repo.ParentCreditLedgerRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * fix/payment-wallet-dispute-integrity: the wallet side of an admin dispute resolution that credits the payer
 * (FULL_CREDIT / PARTIAL_CREDIT / COACH_WARNING with an amount).
 * <ul>
 *   <li>The credit is its own ledger kind ({@code DISPUTE_CREDIT}), not the booking's one
 *       {@code BOOKING_REFUND}: a FULL_CREDIT on an already-refunded booking used to fail on
 *       {@code uq_pcl_booking_refund} with a raw 500, and a dispute credit written while the cancellation
 *       refund was still in the outbox made that refund a silent no-op.</li>
 *   <li>A booking's credits are capped at what was paid minus what already went back
 *       ({@link #remainingRefundable}) — two FULL_CREDIT resolutions on sequential disputes used to credit
 *       200 for a 100 session.</li>
 *   <li>The credit goes through the chargeback credit trace
 *       ({@link ChargebackCreditReversalService#onCreditWritten}): credit for a charge that already lost a
 *       card dispute is taken back at once (the bank refunded the payer), and an open card dispute takes it
 *       back if lost.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DisputeCreditService {

    private final ParentCreditLedgerRepository ledgerRepository;
    private final CreditWalletService creditWalletService;
    private final ChargebackCreditReversalService chargebackCreditReversalService;

    /**
     * What may still be credited for {@code bookingId}: {@code paid} minus its cancellation refund (net of an
     * {@code UPHOLD_COACH} reversal), earlier dispute credits, and a cancellation refund still pending in the
     * outbox. Never negative.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal remainingRefundable(UUID bookingId, BigDecimal paid) {
        if (paid == null || paid.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal refunded = ledgerRepository.sumRefundedForBooking(bookingId);
        return paid.subtract(refunded == null ? BigDecimal.ZERO : refunded).max(BigDecimal.ZERO);
    }

    /**
     * Credits {@code amount} to the payer as a {@code DISPUTE_CREDIT} for {@code bookingId}, then runs it
     * through the chargeback credit trace. The caller has checked it against
     * {@link #remainingRefundable} in the same transaction, under the dispute row's optimistic lock.
     *
     * <p>fix/audit3-wallet-cashout-integrity: cashable only for what the booking was paid from cashable funds —
     * the spend-only share its deduction took (and no refund has yet given back) goes back to the spend-only
     * pool first ({@link CreditWalletService#bookingSpendOnlyOutstanding}); it used to be all cashable, a way to
     * turn spend-only credit into cash.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void credit(Long parentId, UUID bookingId, UUID disputeId, BigDecimal amount, String description) {
        // Lock order: card-dispute rows, then the wallet (as ChargeDisputeWebhookService) — see
        // ChargebackCreditReversalService.lockDisputesForCredit. onCreditWritten below re-uses these locks.
        chargebackCreditReversalService.lockDisputesForCredit(bookingId, null);
        BigDecimal spendOnly = creditWalletService.bookingSpendOnlyOutstanding(parentId, bookingId).min(amount);
        creditWalletService.writeLedgerEntry(parentId, amount, CreditLedgerEntryType.DISPUTE_CREDIT, bookingId,
            description, null, null, spendOnly);
        chargebackCreditReversalService.onCreditWritten(disputeId, bookingId, null);
        log.info("[DISPUTE_CREDIT] dispute={} booking={} payer={} amount={}", disputeId, bookingId, parentId, amount);
    }
}
