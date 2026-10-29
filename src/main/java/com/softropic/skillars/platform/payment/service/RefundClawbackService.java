package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.payment.contract.CreditLedgerEntryType;
import com.softropic.skillars.platform.payment.repo.ParentCreditLedger;
import com.softropic.skillars.platform.payment.repo.ParentCreditLedgerRepository;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchase;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * feat/payment-forfeit-payouts-and-clawback: the payer side of an {@code UPHOLD_COACH} dispute resolution —
 * the admin found the session took place (or the refund was otherwise not owed), so what the payer got back
 * is taken back, as far as it still can be:
 * <ul>
 *   <li><b>wallet refund</b> (the booking's {@code BOOKING_REFUND} ledger row — card / credit bookings,
 *       expired-pack bookings — plus its admin {@code DISPUTE_CREDIT} rows) — debited by the same amount, capped at the wallet's
 *       current balance, never below zero ({@link CreditWalletService#debitRefundReversal});</li>
 *   <li><b>pack session restored</b> (its V195 {@code pack_session_restores} marker) — the session is deducted
 *       from the pack again if it has one left.</li>
 * </ul>
 * Runs in the dispute-resolution transaction ({@code MANDATORY}); the coach side
 * ({@link CoachPayoutAdjustmentService#payInFull}) is the caller's next step.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundClawbackService {

    private final ParentCreditLedgerRepository ledgerRepository;
    private final CreditWalletService creditWalletService;
    private final SessionPackPurchaseRepository sessionPackPurchaseRepository;
    private final PackSessionService packSessionService;
    private final PackRefundAllocationService packRefundAllocationService;
    private final PessimisticLockRetryer lockRetryer;

    /**
     * What the payer had been refunded for the booking and what was taken back.
     *
     * @param refunded          the refund's value: the {@code BOOKING_REFUND} amount, or the pack session's
     *                          share of what the pack was charged
     * @param recovered         what was actually taken back (wallet debit, or the session's value when it was
     *                          re-deducted)
     * @param packSession       true when the refund was a restored pack session
     * @param sessionRededucted true when that session was deducted from the pack again
     */
    public record RefundClawback(BigDecimal refunded, BigDecimal recovered, boolean packSession,
                                 boolean sessionRededucted) {
        /** The part of the refund the platform absorbs. */
        public BigDecimal absorbed() {
            return refunded.subtract(recovered).max(BigDecimal.ZERO);
        }
    }

    /**
     * Claws back the booking's refund. Empty when the booking had no refund to the payer (or it was already
     * reversed) — the caller rejects the resolution.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<RefundClawback> clawBack(UUID bookingId, UUID sessionPackPurchaseId) {
        if (ledgerRepository.existsByReferenceIdAndType(bookingId, CreditLedgerEntryType.BOOKING_REFUND_REVERSAL)) {
            log.info("[REFUND_CLAWBACK] booking={} — refund already reversed", bookingId);
            return Optional.empty();
        }
        // fix/payment-wallet-dispute-integrity: the wallet credit the payer got back for this booking is its
        // BOOKING_REFUND (cancellation / no-show refund) plus any admin DISPUTE_CREDITs (V208 — they used to be
        // the BOOKING_REFUND row). All of it is taken back, as one BOOKING_REFUND_REVERSAL (one per booking).
        Optional<ParentCreditLedger> walletRefund =
            ledgerRepository.findFirstByReferenceIdAndType(bookingId, CreditLedgerEntryType.BOOKING_REFUND);
        List<ParentCreditLedger> disputeCredits =
            ledgerRepository.findByReferenceIdAndTypeOrderByParentId(bookingId, CreditLedgerEntryType.DISPUTE_CREDIT);
        if (walletRefund.isPresent() || !disputeCredits.isEmpty()) {
            List<ParentCreditLedger> credits = new ArrayList<>(disputeCredits);
            walletRefund.ifPresent(credits::add);
            Long parentId = walletRefund.map(ParentCreditLedger::getParentId).orElseGet(() -> disputeCredits.get(0).getParentId());
            BigDecimal refunded = BigDecimal.ZERO;
            BigDecimal spendOnly = BigDecimal.ZERO;
            for (ParentCreditLedger credit : credits) {
                refunded = refunded.add(credit.getAmount());
                if (credit.getSpendOnlyAmount() != null) {
                    spendOnly = spendOnly.add(credit.getSpendOnlyAmount());
                }
            }
            // A spend-only refund is taken back from the spend-only pool first, a cashable one from the cashable
            // pool first.
            BigDecimal recovered = creditWalletService.debitRefundReversal(parentId, bookingId, refunded,
                spendOnly.signum() > 0);
            if (walletRefund.isPresent()) {
                // fix/payment-pack-refund-rounding: an expired-pack session refunded as money is un-settled on the
                // pack's ledger — the caller now pays it to the coach, which settles it again through the payout
                // allocation. Without this the session would count as settled twice and the pack's last payout
                // would come up short. (Any unrecovered part is the platform's absorbed loss, on the dispute.)
                packRefundAllocationService.releaseSessionRefund(sessionPackPurchaseId, bookingId);
            }
            log.info("[REFUND_CLAWBACK] booking={} payer={} refunded={} recovered={}", bookingId, parentId, refunded,
                recovered);
            return Optional.of(new RefundClawback(refunded, recovered, false, false));
        }
        // The V195 once-per-booking restore marker is the record that the pack session was given back.
        if (sessionPackPurchaseId != null && sessionPackPurchaseRepository.existsRestoreMarker(bookingId)) {
            return Optional.of(rededuct(bookingId, sessionPackPurchaseId));
        }
        return Optional.empty();
    }

    private RefundClawback rededuct(UUID bookingId, UUID packId) {
        SessionPackPurchase pack = lockRetryer.withBoundedRetry("RefundClawbackService.rededuct",
            () -> sessionPackPurchaseRepository.findByIdForUpdate(packId).orElse(null));
        if (pack == null) {
            log.error("[REFUND_CLAWBACK] booking={} references pack {} which does not exist — nothing re-deducted",
                bookingId, packId);
            return new RefundClawback(BigDecimal.ZERO, BigDecimal.ZERO, true, false);
        }
        BigDecimal share = pack.perSessionShare();
        BigDecimal sessionValue = share == null ? BigDecimal.ZERO : share;
        // fix/payment-pack-integrity: a clawback, not a spend — taken back even while the pack is paused or
        // frozen by a card dispute (deductSession's guards used to throw payment.packPaused and roll the whole
        // dispute resolution back for as long as the pause lasted). Same transaction, pack lock already held.
        if (!packSessionService.deductSessionForClawback(pack)) {
            log.info("[REFUND_CLAWBACK] booking={} pack={} has no session left — nothing re-deducted", bookingId, packId);
            return new RefundClawback(sessionValue, BigDecimal.ZERO, true, false);
        }
        log.info("[REFUND_CLAWBACK] booking={} pack={} — restored session deducted again", bookingId, packId);
        return new RefundClawback(sessionValue, sessionValue, true, true);
    }
}
