package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.outbox.contract.OutboxMessageHandler;
import com.softropic.skillars.platform.payment.contract.CoachPayoutReversalPayload;
import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.contract.event.PaymentIntegrityAlertEvent;
import com.softropic.skillars.platform.payment.contract.exception.CoachPayoutTransferException;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
import com.softropic.skillars.platform.payment.repo.CoachPayoutRepository;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * skillars-deferred-106 AC8.3 / AC10.2: re-drives a {@code COACH_PAYOUT_REVERSAL} — a dispute upheld
 * after the coach payout {@code RELEASED}. Same idempotency discipline as
 * {@link CoachPayoutTransferHandler}: the {@code coach_payouts} row is the anchor and a reversal runs
 * only if the row is {@code RELEASED}; any other status (already {@code REVERSED}, {@code CANCELLED},
 * …) or a missing row is a no-op. The Stripe key is the second layer — a re-driven row replays the
 * original reversal, never a second.
 *
 * <p>fix/payment-payouts-disputes-webhooks: a reversal of part of the net adds to
 * {@code reversed_amount} and keeps the row {@code RELEASED} (the coach still earned the rest — reporting
 * counts {@code net - reversed_amount}); only once the whole net is reversed does the row become
 * {@code REVERSED}. Each reversal message carries its own id, so the Stripe key
 * {@code reversal-{transferId}-{reversalId}} lets a transfer be partially reversed more than once.
 *
 * <p>fix/payment-payout-recovery: the row is locked ({@code findByIdForUpdate}); a reversal Stripe refuses
 * non-retryably, and any part of the net that never reached Stripe ({@code debt_offset_amount}), become coach
 * debt ({@link CoachPayoutDebtService}) repaid from the coach's next transfers. {@code REVERSAL_FAILED} is
 * now only a RELEASED row with no transfer id to reverse (and pre-V205 rows); a further reversal arriving on
 * one is recorded as debt and alerted rather than ignored.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoachPayoutReversalHandler implements OutboxMessageHandler {

    static final String METRIC_REVERSAL_FAILED = "coach.payout.reversal_failed";

    private final CoachPayoutRepository coachPayoutRepository;
    private final PaymentGateway paymentGateway;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final CoachPayoutDebtService coachPayoutDebtService;

    @Override
    public String aggregateType() {
        return CoachPayoutOutboxSupport.AGGREGATE_TYPE_REVERSAL;
    }

    @Override
    public void handle(String payload) {
        final CoachPayoutReversalPayload p;
        try {
            p = objectMapper.readValue(payload, CoachPayoutReversalPayload.class);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }

        // fix/payment-payout-recovery: locked read, like CoachPayoutTransferHandler (EC-4). Two reversal messages
        // for one booking (an in-app credit and a lost card dispute) are different outbox rows the drain can run
        // in parallel; unlocked, both read reversed_amount=0 and the second write lost the first's.
        CoachPayout row = coachPayoutRepository.findByIdForUpdate(p.bookingId()).orElse(null);
        if (row == null) {
            log.info("[COACH_PAYOUT] no coach_payouts row for booking {} — reversal re-drive is a no-op", p.bookingId());
            return;
        }
        if (CoachPayoutStatus.REVERSAL_FAILED.equals(row.getStatus())) {
            // fix/payment-payout-recovery: a further refund on a payout whose reversal already failed used to be
            // an INFO no-op, so the operator reconciled only the first amount. It is coach debt too.
            BigDecimal more = p.amount() == null ? BigDecimal.ZERO : p.amount().min(row.getEffectiveNetAmount());
            coachPayoutDebtService.recordDebt(row, more, "AFTER_REVERSAL_FAILED");
            return;
        }
        if (!CoachPayoutStatus.RELEASED.equals(row.getStatus())) {
            log.info("[COACH_PAYOUT] booking {} payout is {} (not RELEASED) — reversal re-drive is a no-op",
                p.bookingId(), row.getStatus());
            return;
        }
        BigDecimal requested = p.amount() == null ? BigDecimal.ZERO : p.amount().min(row.getEffectiveNetAmount());
        if (requested.signum() <= 0) {
            log.info("[COACH_PAYOUT] booking {} has nothing left to reverse (net {} reversed {}) — no-op",
                p.bookingId(), row.getNetAmount(), row.getReversedAmount());
            return;
        }
        // fix/payment-payout-recovery: part of the net may never have reached Stripe (withheld to repay coach
        // debt, debt_offset_amount) — that part cannot be reversed there and becomes coach debt again.
        BigDecimal atStripe = requested.min(row.getReversibleAtStripe());
        BigDecimal notAtStripe = requested.subtract(atStripe);
        if (atStripe.signum() <= 0) {
            recordReversed(row, requested);
            coachPayoutRepository.save(row);
            coachPayoutDebtService.recordDebt(row, notAtStripe, "NOTHING_LEFT_AT_STRIPE");
            return;
        }
        if (row.getStripeTransferId() == null) {
            row.setStatus(CoachPayoutStatus.REVERSAL_FAILED);
            row.setLastError("NO_TRANSFER_ID — RELEASED row without a stripe_transfer_id");
            coachPayoutRepository.save(row);
            log.error("[COACH_PAYOUT_REVERSAL] booking={} coach={} is RELEASED but has no stripe_transfer_id "
                + "— cannot reverse, operator must reconcile (runbook: COACH_PAYOUT_REVERSAL)",
                row.getBookingId(), row.getCoachId());
            alertReversalFailed(row, "NO_TRANSFER_ID");
            return;
        }

        try {
            paymentGateway.reverseTransfer(row.getStripeTransferId(), atStripe, reversalKey(row.getStripeTransferId(), p));
            recordReversed(row, requested);
            coachPayoutRepository.save(row);
            log.info("[COACH_PAYOUT] reversed booking={} coach={} amount={} totalReversed={} status={} transferId={}",
                row.getBookingId(), row.getCoachId(), atStripe, row.getReversedAmount(), row.getStatus(),
                row.getStripeTransferId());
            coachPayoutDebtService.recordDebt(row, notAtStripe, "NOTHING_LEFT_AT_STRIPE");
        } catch (CoachPayoutTransferException e) {
            if (e.isRetryable()) {
                throw e;
            }
            // AC8.3: non-retryable — the coach withdrew the balance / the account closed.
            // fix/payment-payout-recovery: the platform already refunded the payer, so the coach's share is
            // recorded as coach debt (deducted from the coach's next transfers, alerted) instead of the row
            // going REVERSAL_FAILED with nothing following it up. The row books the reversal as done
            // (reversed_amount), so reporting stops counting money the coach now owes back, and a later refund
            // on the booking is still handled.
            recordReversed(row, requested);
            row.setLastError(truncate("REVERSAL_DEBT " + e.getReason() + ": " + e.getCause()));
            row.setAttempts(row.getAttempts() + 1);
            coachPayoutRepository.save(row);
            meterRegistry.counter(METRIC_REVERSAL_FAILED, "reason", e.getReason()).increment();
            log.error("[COACH_PAYOUT_REVERSAL] booking={} coach={} reason={} — Stripe refused the reversal of {}, "
                + "recorded as coach debt (runbook: COACH_PAYOUT_REVERSAL)",
                row.getBookingId(), row.getCoachId(), e.getReason(), atStripe);
            coachPayoutDebtService.recordDebt(row, requested, e.getReason());
        }
    }

    /** Books {@code amount} as clawed back from the coach; the whole net clawed back makes the row REVERSED. */
    private static void recordReversed(CoachPayout row, BigDecimal amount) {
        BigDecimal reversed = (row.getReversedAmount() == null ? BigDecimal.ZERO : row.getReversedAmount()).add(amount);
        row.setReversedAmount(reversed);
        row.setReversedAt(Instant.now());
        if (reversed.compareTo(row.getNetAmount()) >= 0) {
            row.setStatus(CoachPayoutStatus.REVERSED);
        }
    }

    /** Per reversal instance; a payload from before the reversalId field falls back to the old per-transfer key. */
    static String reversalKey(String transferId, CoachPayoutReversalPayload p) {
        return p.reversalId() == null ? "reversal-" + transferId : "reversal-" + transferId + "-" + p.reversalId();
    }

    /** fix/payment-payouts-disputes-webhooks: an admin alert, one OPEN per booking (AFTER_COMMIT). */
    private void alertReversalFailed(CoachPayout row, String reason) {
        eventPublisher.publishEvent(new PaymentIntegrityAlertEvent(this,
            PaymentIntegrityAlertEvent.Kind.PAYOUT_REVERSAL_FAILED, row.getBookingId().toString(),
            PaymentIntegrityAlertEvent.Reference.BOOKING, reason));
    }

    private static String truncate(String s) {
        return s != null && s.length() > 500 ? s.substring(0, 500) : s;
    }
}
