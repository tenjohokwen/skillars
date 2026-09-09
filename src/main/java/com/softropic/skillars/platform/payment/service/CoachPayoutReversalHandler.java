package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.outbox.contract.OutboxMessageHandler;
import com.softropic.skillars.platform.payment.contract.CoachPayoutReversalPayload;
import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
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
 * skillars-deferred-106 AC8.3 / AC10.2: re-drives a {@code COACH_PAYOUT_REVERSAL} — a dispute upheld
 * after the coach payout {@code RELEASED}. Same idempotency discipline as
 * {@link CoachPayoutTransferHandler}: the {@code coach_payouts} row is the anchor and a reversal runs
 * only if the row is {@code RELEASED}; any other status (already {@code REVERSED}, {@code CANCELLED},
 * …) or a missing row is a no-op. The deterministic {@code reversal-{stripeTransferId}} Stripe key
 * (AC5.3) is the second layer — a re-driven row replays the original reversal, never a second.
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

        CoachPayout row = coachPayoutRepository.findById(p.bookingId()).orElse(null);
        if (row == null) {
            log.info("[COACH_PAYOUT] no coach_payouts row for booking {} — reversal re-drive is a no-op", p.bookingId());
            return;
        }
        if (!CoachPayoutStatus.RELEASED.equals(row.getStatus())) {
            log.info("[COACH_PAYOUT] booking {} payout is {} (not RELEASED) — reversal re-drive is a no-op",
                p.bookingId(), row.getStatus());
            return;
        }
        if (row.getStripeTransferId() == null) {
            row.setStatus(CoachPayoutStatus.REVERSAL_FAILED);
            row.setLastError("NO_TRANSFER_ID — RELEASED row without a stripe_transfer_id");
            coachPayoutRepository.save(row);
            log.error("[COACH_PAYOUT_REVERSAL] booking={} coach={} is RELEASED but has no stripe_transfer_id "
                + "— cannot reverse, operator must reconcile (runbook: COACH_PAYOUT_REVERSAL)",
                row.getBookingId(), row.getCoachId());
            return;
        }

        try {
            paymentGateway.reverseTransfer(row.getStripeTransferId(), p.amount());
            row.setStatus(CoachPayoutStatus.REVERSED);
            row.setReversedAt(Instant.now());
            coachPayoutRepository.save(row);
            log.info("[COACH_PAYOUT] reversed booking={} coach={} amount={} transferId={}",
                row.getBookingId(), row.getCoachId(), p.amount(), row.getStripeTransferId());
        } catch (CoachPayoutTransferException e) {
            if (e.isRetryable()) {
                throw e;
            }
            // AC8.3: non-retryable — coach withdrew the balance / account closed. REVERSAL_FAILED,
            // metric + ERROR carrying bookingId + coachId, operator follow-up.
            row.setStatus(CoachPayoutStatus.REVERSAL_FAILED);
            row.setLastError(e.getReason() + ": " + truncate(String.valueOf(e.getCause())));
            row.setAttempts(row.getAttempts() + 1);
            coachPayoutRepository.save(row);
            meterRegistry.counter(METRIC_REVERSAL_FAILED, "reason", e.getReason()).increment();
            log.error("[COACH_PAYOUT_REVERSAL] booking={} coach={} reason={} — reversal failed, manual "
                + "follow-up required (runbook: COACH_PAYOUT_REVERSAL)",
                row.getBookingId(), row.getCoachId(), e.getReason());
        }
    }

    private static String truncate(String s) {
        return s != null && s.length() > 500 ? s.substring(0, 500) : s;
    }
}
