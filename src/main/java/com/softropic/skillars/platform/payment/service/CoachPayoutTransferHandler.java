package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.outbox.contract.OutboxMessageHandler;
import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
import com.softropic.skillars.platform.payment.contract.CoachPayoutTransferPayload;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.contract.exception.CoachPayoutTransferException;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
import com.softropic.skillars.platform.payment.repo.CoachPayoutRepository;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
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

    private final CoachPayoutRepository coachPayoutRepository;
    private final PaymentGateway paymentGateway;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

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
        if (row.getReleaseAfter() != null && row.getReleaseAfter().isAfter(Instant.now())) {
            // AC4.3: still inside the hold window — throw so the outbox backoff re-drives it later.
            throw new IllegalStateException("coach payout for booking " + p.bookingId()
                + " is held until " + row.getReleaseAfter() + " — deferring");
        }

        try {
            String transferId = paymentGateway.transferToCoach(
                row.getBookingId(), row.getCoachId(), row.getNetAmount(), row.getCurrency());
            row.setStatus(CoachPayoutStatus.RELEASED);
            row.setStripeTransferId(transferId);
            row.setReleasedAt(Instant.now());
            coachPayoutRepository.save(row);
            log.info("[COACH_PAYOUT] released booking={} coach={} net={} transferId={}",
                row.getBookingId(), row.getCoachId(), row.getNetAmount(), transferId);
        } catch (CoachPayoutTransferException e) {
            if (e.isRetryable()) {
                // AC8.1: throw so the outbox backoff re-drives; [OUTBOX_STUCK] ERROR after 10 attempts.
                throw e;
            }
            // AC8.2: non-retryable — the destination account cannot receive. HOLD the row, emit the
            // disconnect-frequency signal, WARN with the runbook pointer, and do NOT throw (a HOLD is
            // a decision; re-driving every drain would just re-log).
            row.setStatus(CoachPayoutStatus.HOLD);
            row.setLastError(e.getReason() + ": " + truncate(String.valueOf(e.getCause())));
            row.setAttempts(row.getAttempts() + 1);
            coachPayoutRepository.save(row);
            meterRegistry.counter(METRIC_HELD, "reason", e.getReason()).increment();
            log.warn("[COACH_PAYOUT_HELD] booking={} coach={} reason={} — payout blocked, operator must "
                + "reconcile (runbook: COACH_PAYOUT_HELD)", row.getBookingId(), row.getCoachId(), e.getReason());
        }
    }

    private static String truncate(String s) {
        return s != null && s.length() > 500 ? s.substring(0, 500) : s;
    }
}
