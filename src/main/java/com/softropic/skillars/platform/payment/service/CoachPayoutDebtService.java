package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.contract.event.PaymentIntegrityAlertEvent;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
import com.softropic.skillars.platform.payment.repo.CoachPayoutDebt;
import com.softropic.skillars.platform.payment.repo.CoachPayoutDebtRepository;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * fix/payment-payout-recovery: the coach debt ledger ({@code payment.coach_payout_debts}). A refund the
 * platform paid to a payer whose coach-side reversal could not be taken back at Stripe (the coach withdrew
 * the balance, the account closed, or the transfer carried less than the reversal because part of it already
 * repaid an older debt) is recorded here and raised as a {@code PAYOUT_REVERSAL_FAILED} alert, instead of
 * being left with the coach unrecorded. The coach's next transfers repay it ({@link #planOffset} /
 * {@link #applyOffset}); an {@code UPHOLD_COACH} on the booking waives it ({@link #waiveForBooking}).
 *
 * <p>Every method runs in the caller's transaction ({@code MANDATORY}) and the caller already holds the
 * {@code coach_payouts} row lock — payout row first, debt rows second (see {@link CoachPayoutDebtRepository}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoachPayoutDebtService {

    static final String METRIC_DEBT_RECORDED = "coach.payout.debt_recorded";

    private final CoachPayoutDebtRepository debtRepository;
    private final MeterRegistry meterRegistry;
    private final ApplicationEventPublisher eventPublisher;

    /** What a transfer withholds to repay the coach's OPEN debts, and the locked debts it repays. */
    public record Offset(BigDecimal amount, List<CoachPayoutDebt> debts) {
        static final Offset NONE = new Offset(BigDecimal.ZERO, List.of());
    }

    /**
     * Records {@code amount} the coach owes back for {@code row}'s booking, and alerts (one OPEN
     * {@code PAYOUT_REVERSAL_FAILED} alert per booking, AFTER_COMMIT).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordDebt(CoachPayout row, BigDecimal amount, String reason) {
        if (amount == null || amount.signum() <= 0) {
            return;
        }
        debtRepository.save(CoachPayoutDebt.open(row.getCoachId(), row.getBookingId(), amount, reason));
        meterRegistry.counter(METRIC_DEBT_RECORDED, "reason", reason == null ? "UNKNOWN" : reason).increment();
        log.error("[COACH_PAYOUT_DEBT] booking={} coach={} amount={} reason={} — reversal could not take the money "
            + "back at Stripe; recorded as coach debt, deducted from the coach's next transfers "
            + "(runbook: COACH_PAYOUT_REVERSAL)", row.getBookingId(), row.getCoachId(), amount, reason);
        eventPublisher.publishEvent(new PaymentIntegrityAlertEvent(this,
            PaymentIntegrityAlertEvent.Kind.PAYOUT_REVERSAL_FAILED, row.getBookingId().toString(),
            PaymentIntegrityAlertEvent.Reference.BOOKING, "COACH_DEBT " + amount + " " + reason));
    }

    /**
     * Locks the coach's OPEN debts (oldest first) and works out how much of {@code net} repays them. Writes
     * nothing: a transfer that then fails non-retryably commits its HOLD without having repaid anything —
     * {@link #applyOffset} runs only once the transfer succeeded.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Offset planOffset(UUID coachId, BigDecimal net) {
        if (net == null || net.signum() <= 0) {
            return Offset.NONE;
        }
        List<CoachPayoutDebt> debts = debtRepository.findOpenByCoachIdForUpdate(coachId);
        if (debts.isEmpty()) {
            return Offset.NONE;
        }
        BigDecimal outstanding = debts.stream().map(CoachPayoutDebt::getOutstanding)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal amount = outstanding.min(net);
        return amount.signum() <= 0 ? Offset.NONE : new Offset(amount, debts);
    }

    /** Marks {@code offset} as repaid, oldest debt first. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyOffset(CoachPayout row, Offset offset) {
        BigDecimal left = offset.amount();
        for (CoachPayoutDebt debt : offset.debts()) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal take = left.min(debt.getOutstanding());
            debt.recover(take);
            debtRepository.save(debt);
            left = left.subtract(take);
        }
        if (offset.amount().signum() > 0) {
            log.warn("[COACH_PAYOUT_DEBT_OFFSET] booking={} coach={} — {} of the net {} withheld to repay coach debt",
                row.getBookingId(), row.getCoachId(), offset.amount(), row.getNetAmount());
        }
    }

    /**
     * {@code UPHOLD_COACH}: the coach is owed the booking's full net after all, so what is still outstanding
     * on its debts is written off (WAIVED). What was already repaid stays repaid — the caller tops the coach
     * up for it.
     *
     * @return the outstanding amount waived
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal waiveForBooking(UUID bookingId) {
        BigDecimal waived = BigDecimal.ZERO;
        for (CoachPayoutDebt debt : debtRepository.findOpenByBookingIdForUpdate(bookingId)) {
            waived = waived.add(debt.getOutstanding());
            debt.setStatus(CoachPayoutDebt.STATUS_WAIVED);
            debtRepository.save(debt);
        }
        if (waived.signum() > 0) {
            log.info("[COACH_PAYOUT_DEBT_WAIVED] booking={} — {} of coach debt waived by an UPHOLD_COACH", bookingId,
                waived);
        }
        return waived;
    }
}
