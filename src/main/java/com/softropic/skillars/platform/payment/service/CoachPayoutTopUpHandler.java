package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.outbox.contract.OutboxMessageHandler;
import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
import com.softropic.skillars.platform.payment.contract.CoachPayoutTopUpPayload;
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
import java.math.RoundingMode;

/**
 * fix/payment-payout-recovery: pays a {@code COACH_PAYOUT_TOPUP} — the part of the coach's full net an
 * {@code UPHOLD_COACH} resolution found missing from a released payout (an earlier refund reversed it, or it
 * released after a pending cut). Runs in {@code OutboxRowProcessor}'s {@code REQUIRES_NEW} transaction, like
 * {@link CoachPayoutTransferHandler}.
 *
 * <p><strong>Idempotent.</strong> The row is locked and what is still owed is re-derived from it
 * ({@code original_net - (net - reversed)}); a top-up that already landed leaves nothing owed, so a duplicate
 * message is a no-op. The Stripe transfer uses the payload's {@code topUpId} as {@code transfer_group} and key
 * ({@code transfer-{topUpId}}), so a re-drive after a lost response replays it — and past Stripe's 24h key
 * window the gateway adopts the transfer it finds in that group.
 *
 * <p>On success the top-up is folded back into the row: it first undoes {@code reversed_amount}, the rest
 * raises {@code net_amount} (towards the original gross/commission), and a REVERSED row is RELEASED again, so
 * {@link CoachPayout#getEffectiveNetAmount()} and the reporting queries count it with no change.
 *
 * <p>A transfer the account cannot receive is re-driven (outbox backoff) with a {@code PAYOUT_HELD} alert —
 * the row is RELEASED, so there is no HOLD state to park it in.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoachPayoutTopUpHandler implements OutboxMessageHandler {

    static final String METRIC_TOPUP_RETRY = "coach.payout.topup_retry";

    private final CoachPayoutRepository coachPayoutRepository;
    private final PaymentGateway paymentGateway;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public String aggregateType() {
        return CoachPayoutOutboxSupport.AGGREGATE_TYPE_TOPUP;
    }

    @Override
    public void handle(String payload) {
        final CoachPayoutTopUpPayload p;
        try {
            p = objectMapper.readValue(payload, CoachPayoutTopUpPayload.class);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        CoachPayout row = coachPayoutRepository.findByIdForUpdate(p.bookingId()).orElse(null);
        if (row == null) {
            log.info("[COACH_PAYOUT_TOPUP] no coach_payouts row for booking {} — top-up is a no-op", p.bookingId());
            return;
        }
        if (!CoachPayoutStatus.RELEASED.equals(row.getStatus()) && !CoachPayoutStatus.REVERSED.equals(row.getStatus())) {
            log.warn("[COACH_PAYOUT_TOPUP] booking={} payout is {} (not RELEASED/REVERSED) — top-up is a no-op",
                p.bookingId(), row.getStatus());
            return;
        }
        BigDecimal owed = row.getOriginalNet().subtract(row.getEffectiveNetAmount());
        BigDecimal amount = p.amount() == null ? owed : p.amount().min(owed);
        if (amount.signum() <= 0) {
            log.info("[COACH_PAYOUT_TOPUP] booking={} already carries its full net {} — top-up is a no-op",
                p.bookingId(), row.getOriginalNet());
            return;
        }
        final String transferId;
        try {
            transferId = paymentGateway.transferToCoach(p.topUpId(), row.getCoachId(), amount, row.getCurrency());
        } catch (CoachPayoutTransferException e) {
            meterRegistry.counter(METRIC_TOPUP_RETRY, "reason", e.getReason()).increment();
            log.warn("[COACH_PAYOUT_TOPUP] booking={} coach={} reason={} retryable={} — top-up transfer will be "
                + "re-driven", row.getBookingId(), row.getCoachId(), e.getReason(), e.isRetryable());
            if (!e.isRetryable()) {
                eventPublisher.publishEvent(new PaymentIntegrityAlertEvent(this,
                    PaymentIntegrityAlertEvent.Kind.PAYOUT_HELD, row.getBookingId().toString(),
                    PaymentIntegrityAlertEvent.Reference.BOOKING, "TOPUP_" + e.getReason()));
            }
            throw e;
        }
        applyTopUp(row, amount);
        row.setTopupTransferId(transferId);
        coachPayoutRepository.save(row);
        log.info("[COACH_PAYOUT_TOPUP] booking={} coach={} topped up {} (transferId={}) — payout {} now carries {}",
            row.getBookingId(), row.getCoachId(), amount, transferId, row.getStatus(), row.getEffectiveNetAmount());
    }

    /** Folds a paid top-up into the row: undoes the reversal first, raises the net with the rest. */
    static void applyTopUp(CoachPayout row, BigDecimal amount) {
        BigDecimal reversed = row.getReversedAmount() == null ? BigDecimal.ZERO : row.getReversedAmount();
        BigDecimal fromReversal = amount.min(reversed);
        row.setReversedAmount(reversed.subtract(fromReversal));
        BigDecimal rest = amount.subtract(fromReversal);
        if (rest.signum() > 0) {
            BigDecimal newNet = row.getNetAmount().add(rest);
            if (newNet.compareTo(row.getOriginalNet()) >= 0) {
                row.setGrossAmount(row.getOriginalGross());
                row.setCommissionAmount(row.getOriginalCommission());
            } else if (row.getNetAmount().signum() > 0) {
                BigDecimal gross = row.getGrossAmount().multiply(newNet)
                    .divide(row.getNetAmount(), 2, RoundingMode.HALF_UP).max(newNet);
                row.setGrossAmount(gross);
                row.setCommissionAmount(gross.subtract(newNet));
            }
            row.setNetAmount(newNet);
        }
        BigDecimal topped = row.getTopupAmount() == null ? BigDecimal.ZERO : row.getTopupAmount();
        row.setTopupAmount(topped.add(amount));
        if (CoachPayoutStatus.REVERSED.equals(row.getStatus())) {
            row.setStatus(CoachPayoutStatus.RELEASED);
        }
    }
}
