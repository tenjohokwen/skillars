package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.outbox.contract.OutboxMessageHandler;
import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
import com.softropic.skillars.platform.payment.contract.CoachPayoutTransferPayload;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.contract.event.PaymentIntegrityAlertEvent;
import com.softropic.skillars.platform.payment.contract.exception.CoachPayoutTransferException;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
import com.softropic.skillars.platform.payment.repo.CoachPayoutFreezeRepository;
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
 * skillars-deferred-106 AC7 / AC8: re-drives a {@code COACH_PAYOUT_TRANSFER}. Runs inside
 * {@code OutboxRowProcessor.claimAndHandle()}'s {@code REQUIRES_NEW} transaction — a repo save here
 * commits with the outbox row's deletion; a {@code throw} rolls both back and the outbox records
 * {@code attempts++} / {@code last_error} in its own transaction and re-drives with backoff.
 *
 * <p><strong>Idempotent per booking</strong> (AC7.1/7.2): the {@code payment.coach_payouts} row is
 * the anchor. The handler proceeds <em>only</em> if the row is {@code PENDING_RELEASE} and
 * {@code release_after} has passed. For any other status — {@code RELEASED}, {@code HOLD},
 * {@code CANCELLED}, {@code REVERSED}, {@code REVERSAL_FAILED}, {@code FAILED_PERMANENT} — or a
 * missing row, it returns without calling Stripe (a duplicate {@code BookingCompletedEvent} or a
 * re-driven row is a no-op). {@code BookingCompletedEvent} exactly-once is NOT assumed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoachPayoutTransferHandler implements OutboxMessageHandler {

    static final String METRIC_HELD = "coach.payout.held";
    static final String METRIC_TRANSFER_RETRY = "coach.payout.transfer_retry";
    static final String METRIC_FROZEN = "coach.payout.frozen";

    private final CoachPayoutRepository coachPayoutRepository;
    private final CoachPayoutFreezeRepository coachPayoutFreezeRepository;
    private final PaymentGateway paymentGateway;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final CoachPayoutDebtService coachPayoutDebtService;

    @Override
    public String aggregateType() {
        return CoachPayoutOutboxSupport.AGGREGATE_TYPE_TRANSFER;
    }

    @Override
    public void handle(String payload) {
        final CoachPayoutTransferPayload p;
        try {
            p = objectMapper.readValue(payload, CoachPayoutTransferPayload.class);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }

        // EC-4: locked read. DisputeService.reconcileCoachPayout takes a PESSIMISTIC_WRITE lock on
        // this row to flip PENDING_RELEASE -> CANCELLED atomically with the dispute resolution; the
        // handler must contend for the same lock or that mutual exclusion is only half-there. Without
        // it, a drain that has already read PENDING_RELEASE can fire the transfer after the dispute
        // has cancelled the payout and refunded the parent — money to a coach whose booking was
        // disputed. Serializing here makes the handler re-read CANCELLED and no-op.
        CoachPayout row = coachPayoutRepository.findByIdForUpdate(p.bookingId()).orElse(null);
        if (row == null) {
            log.info("[COACH_PAYOUT] no coach_payouts row for booking {} — re-drive is a no-op", p.bookingId());
            return;
        }
        if (!CoachPayoutStatus.isPayable(row.getStatus())) {
            log.info("[COACH_PAYOUT] booking {} payout is {} (not PENDING_RELEASE) — re-drive is a no-op",
                p.bookingId(), row.getStatus());
            return;
        }
        // fix/payment-payouts-disputes-webhooks: an open in-app or card dispute holds the payout. The
        // writers flip the row FROZEN themselves; this catches a row put back to PENDING_RELEASE by hand.
        if (row.hasPayoutFreezeReason()) {
            row.setStatus(CoachPayoutStatus.FROZEN);
            row.setLastError(row.isDisputeFrozen() ? CoachPayoutAdjustmentService.REASON_DISPUTE_OPEN
                : CoachPayoutAdjustmentService.REASON_CHARGEBACK_OPEN);
            coachPayoutRepository.save(row);
            meterRegistry.counter(METRIC_FROZEN).increment();
            log.warn("[COACH_PAYOUT_FROZEN] booking={} coach={} reason={} — transfer NOT made while the dispute is open",
                row.getBookingId(), row.getCoachId(), row.getLastError());
            return;
        }
        // feat/coach-payouts-pack-and-credit (DECISIONS.md item 2): the release-time gate for a CSAM
        // safety hold. Read under this row's lock, immediately before the transfer, so a hold that landed
        // after the payout was enqueued (or a freeze UPDATE that skipped a row it could not see yet) still
        // stops the money here. FROZEN is not payable, so this outbox message is consumed; an admin
        // reinstatement re-enqueues one (CoachPayoutFreezeService.liftOnReinstatement).
        // FOR SHARE (see CoachPayoutFreezeRepository.findActiveForShare): a concurrent reinstatement waits
        // for this transaction, so the FROZEN row written below is never missed by its re-enqueue.
        if (coachPayoutFreezeRepository.isFrozenForShare(row.getCoachId())) {
            row.setStatus(CoachPayoutStatus.FROZEN);
            row.setLastError(CoachPayoutFreezeService.REASON_SAFETY_HOLD);
            coachPayoutRepository.save(row);
            meterRegistry.counter(METRIC_FROZEN).increment();
            log.warn("[COACH_PAYOUT_FROZEN] booking={} coach={} — coach is under a safety hold, transfer NOT "
                + "made; the payout is FROZEN until an admin reinstates the coach", row.getBookingId(), row.getCoachId());
            return;
        }
        if (row.getReleaseAfter() != null && row.getReleaseAfter().isAfter(Instant.now())) {
            // AC4.3: still inside the hold window — throw so the outbox backoff re-drives it later.
            throw new IllegalStateException("coach payout for booking " + p.bookingId()
                + " is held until " + row.getReleaseAfter() + " — deferring");
        }

        // fix/payment-payout-recovery: money a refused reversal left with the coach (coach_payout_debts) is
        // repaid from this payout first. Planned under the debt rows' lock but written only after the transfer
        // succeeds, so a HOLD commits without having repaid anything.
        CoachPayoutDebtService.Offset offset = coachPayoutDebtService.planOffset(row.getCoachId(), row.getNetAmount());
        BigDecimal toTransfer = row.getNetAmount().subtract(offset.amount());
        try {
            String transferId = toTransfer.signum() > 0
                ? paymentGateway.transferToCoach(row.getBookingId(), row.getCoachId(), toTransfer, row.getCurrency())
                : null; // the whole net repays coach debt: nothing to send
            coachPayoutDebtService.applyOffset(row, offset);
            row.setDebtOffsetAmount(offset.amount());
            row.setStatus(CoachPayoutStatus.RELEASED);
            row.setStripeTransferId(transferId);
            row.setReleasedAt(Instant.now());
            coachPayoutRepository.save(row);
            log.info("[COACH_PAYOUT] released booking={} coach={} net={} debtOffset={} transferred={} transferId={}",
                row.getBookingId(), row.getCoachId(), row.getNetAmount(), offset.amount(), toTransfer, transferId);
        } catch (CoachPayoutTransferException e) {
            if (e.isRetryable()) {
                // AC8.1: throw so the outbox backoff re-drives; [OUTBOX_STUCK] ERROR after 10 attempts.
                // A transfer with no charge behind it (a credit-funded session) draws on the platform's
                // AVAILABLE balance, so BALANCE_INSUFFICIENT is expected while charges settle — counted
                // per reason so a persistent shortfall is visible before [OUTBOX_STUCK] fires.
                meterRegistry.counter(METRIC_TRANSFER_RETRY, "reason", e.getReason()).increment();
                log.warn("[COACH_PAYOUT_RETRY] booking={} coach={} reason={} — transfer will be re-driven",
                    row.getBookingId(), row.getCoachId(), e.getReason());
                throw e;
            }
            // AC8.2: non-retryable — the destination account cannot receive. HOLD the row, emit the
            // disconnect-frequency signal, WARN with the runbook pointer, and do NOT throw (a HOLD is
            // a decision; re-driving every drain would just re-log).
            // fix/payment-payout-recovery: an account-side HOLD is re-driven automatically once the account can
            // receive transfers again (CoachPayoutHoldRecoveryService, on account.updated / onboarding complete)
            // or by an admin; a TRANSFER_*_MISMATCH HOLD is left for the operator.
            row.setStatus(CoachPayoutStatus.HOLD);
            row.setLastError(e.getReason() + ": " + truncate(String.valueOf(e.getCause())));
            row.setAttempts(row.getAttempts() + 1);
            coachPayoutRepository.save(row);
            meterRegistry.counter(METRIC_HELD, "reason", e.getReason()).increment();
            log.warn("[COACH_PAYOUT_HELD] booking={} coach={} reason={} — payout blocked until the account can "
                + "receive transfers (runbook: COACH_PAYOUT_HELD)", row.getBookingId(), row.getCoachId(), e.getReason());
            // fix/payment-payouts-disputes-webhooks: an admin alert, one OPEN per booking (AFTER_COMMIT).
            eventPublisher.publishEvent(new PaymentIntegrityAlertEvent(this, PaymentIntegrityAlertEvent.Kind.PAYOUT_HELD,
                row.getBookingId().toString(), PaymentIntegrityAlertEvent.Reference.BOOKING, e.getReason()));
        }
    }

    private static String truncate(String s) {
        return s != null && s.length() > 500 ? s.substring(0, 500) : s;
    }
}
