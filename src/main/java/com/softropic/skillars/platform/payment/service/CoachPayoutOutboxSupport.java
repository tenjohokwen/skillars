package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.outbox.service.OutboxService;
import com.softropic.skillars.platform.payment.contract.CoachPayoutReversalPayload;
import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
import com.softropic.skillars.platform.payment.contract.CoachPayoutTransferPayload;
import com.softropic.skillars.platform.payment.repo.BookingPayment;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
import com.softropic.skillars.platform.payment.repo.CoachPayoutRepository;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccount;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccountRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * skillars-deferred-106 AC4: writes the {@code payment.coach_payouts} row and enqueues the outbox
 * message for a completed session's coach payout — <strong>atomically with the booking's completion
 * write</strong>. Modelled exactly on {@code RefundOutboxSupport}: {@code @Transactional(MANDATORY)}
 * so it can only be called from inside an active transaction (the
 * {@code @TransactionalEventListener(BEFORE_COMMIT)} in {@link CoachPayoutEnqueueListener}), then
 * {@code outboxService.requestDrainAfterCommit()}.
 *
 * <p><strong>Do not</strong> make this method (or the handlers) self-invoke a
 * {@code @Transactional(REQUIRES_NEW) drain()} — see {@code OutboxService} / {@code OutboxChunkProcessor}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoachPayoutOutboxSupport {

    public static final String AGGREGATE_TYPE_TRANSFER = "COACH_PAYOUT_TRANSFER";
    public static final String AGGREGATE_TYPE_REVERSAL = "COACH_PAYOUT_REVERSAL";

    /** AC10.4 bound: 14 days = disputes.submissionWindowDays, the longest sane hold. */
    private static final long HOLD_HOURS_DEFAULT = 48L;
    private static final long HOLD_HOURS_MIN = 0L;
    private static final long HOLD_HOURS_MAX = 336L;

    private final CoachPayoutRepository coachPayoutRepository;
    private final BookingPaymentRepository bookingPaymentRepository;
    private final CoachStripeAccountRepository coachStripeAccountRepository;
    private final OutboxService outboxService;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;

    /**
     * AC3.2 / AC4.1: insert the {@code coach_payouts} row {@code PENDING_RELEASE} and enqueue a
     * {@code COACH_PAYOUT_TRANSFER} message, both in the caller's (completion) transaction.
     *
     * <p>Idempotent by the {@code booking_id} PK: a duplicate {@code BookingCompletedEvent} that
     * arrives before the first transaction committed finds the row already present and is a no-op
     * (the handler is idempotent regardless — AC7.1/7.2).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueuePayout(UUID bookingId, UUID coachId) {
        if (coachPayoutRepository.existsById(bookingId)) {
            log.info("[COACH_PAYOUT] coach_payouts row already exists for booking {} — enqueue is a no-op", bookingId);
            return;
        }

        BookingPayment bp = bookingPaymentRepository.findById(bookingId).orElse(null);
        if (bp == null) {
            // A completed booking with no booking_payments row never went through a charge path.
            // Nothing to pay the coach through Stripe; coach earnings for such bookings are out of
            // scope (design doc §0.1).
            log.warn("[COACH_PAYOUT] no booking_payments row for completed booking {} — no payout enqueued", bookingId);
            return;
        }

        BigDecimal stripeCharged = bp.getStripeCharged() == null ? BigDecimal.ZERO : bp.getStripeCharged();
        String currency = resolveCurrency();

        if (stripeCharged.signum() <= 0) {
            // AC4.4: fully credit-covered or pack-funded — no Stripe charge happened, so no transfer.
            // Write a directly-CANCELLED row (reason NO_STRIPE_CHARGE) for auditability; do NOT enqueue.
            CoachPayout row = newRow(bookingId, coachId, currency, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                Instant.now());
            row.setStatus(CoachPayoutStatus.CANCELLED);
            row.setLastError("NO_STRIPE_CHARGE");
            coachPayoutRepository.save(row);
            log.info("[COACH_PAYOUT] booking {} was fully credit/pack funded (stripe_charged=0) — "
                + "coach_payouts row written CANCELLED (NO_STRIPE_CHARGE), no transfer", bookingId);
            return;
        }

        // AC4.2 / AC4.4: net is computed on the CARD-CHARGED portion only, at the rate LOCKED AT
        // CAPTURE (booking_payments.commission_rate), never platform.commission.rate re-read now.
        BigDecimal rate = bp.getCommissionRate() != null ? bp.getCommissionRate() : liveCommissionRate();
        BigDecimal gross = stripeCharged;
        BigDecimal commission = gross.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal net = gross.subtract(commission);

        long holdHours = configService.getBoundedLong(
            "payment.payout.hold_hours", HOLD_HOURS_DEFAULT, HOLD_HOURS_MIN, HOLD_HOURS_MAX);
        Instant releaseAfter = Instant.now().plus(Duration.ofHours(holdHours));

        CoachPayout row = newRow(bookingId, coachId, currency, gross, commission, net, releaseAfter);
        row.setStatus(CoachPayoutStatus.PENDING_RELEASE);
        row.setCoachStripeAccountId(resolveCoachAccountIdBestEffort(coachId));
        coachPayoutRepository.save(row);

        enqueue(AGGREGATE_TYPE_TRANSFER, new CoachPayoutTransferPayload(bookingId), releaseAfter, bookingId);
        log.info("[COACH_PAYOUT] enqueued transfer for booking {} coach {} net {} releaseAfter {}",
            bookingId, coachId, net, releaseAfter);
    }

    /**
     * AC10.2: a dispute upheld after the payout has {@code RELEASED} — enqueue a
     * {@code COACH_PAYOUT_REVERSAL} for {@code amount}, in the dispute-resolution transaction. The
     * {@code coach_payouts} row stays {@code RELEASED} until {@code CoachPayoutReversalHandler}
     * flips it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueReversal(UUID bookingId, BigDecimal amount) {
        enqueue(AGGREGATE_TYPE_REVERSAL, new CoachPayoutReversalPayload(bookingId, amount), null, bookingId);
        log.info("[COACH_PAYOUT] enqueued reversal for booking {} amount {}", bookingId, amount);
    }

    private void enqueue(String aggregateType, Object payload, Instant notBefore, UUID bookingId) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            if (notBefore != null) {
                outboxService.enqueue(aggregateType, json, notBefore);
            } else {
                outboxService.enqueue(aggregateType, json);
            }
            outboxService.requestDrainAfterCommit();
        } catch (JsonProcessingException e) {
            // A one/two-field payload; serialisation should never fail. If it does, the coach_payouts
            // row is still written (PENDING_RELEASE) — a loud log, then an operator re-enqueues.
            log.error("[COACH_PAYOUT_ENQUEUE_FAILED] aggregateType={} booking={} — payout row written but "
                + "outbox message NOT enqueued, operator must re-drive", aggregateType, bookingId, e);
        }
    }

    private CoachPayout newRow(UUID bookingId, UUID coachId, String currency,
                               BigDecimal gross, BigDecimal commission, BigDecimal net, Instant releaseAfter) {
        CoachPayout row = new CoachPayout();
        row.setBookingId(bookingId);
        row.setCoachId(coachId);
        row.setCurrency(currency);
        row.setGrossAmount(gross);
        row.setCommissionAmount(commission);
        row.setNetAmount(net);
        row.setReleaseAfter(releaseAfter);
        row.setAttempts(0);
        return row;
    }

    private String resolveCoachAccountIdBestEffort(UUID coachId) {
        return coachStripeAccountRepository.findById(coachId)
            .filter(a -> "COMPLETE".equals(a.getOnboardingStatus()) && a.isChargesEnabled())
            .map(CoachStripeAccount::getStripeAccountId)
            .orElse(null);
    }

    private BigDecimal liveCommissionRate() {
        return new BigDecimal(configService.getString("platform.commission.rate"));
    }

    private String resolveCurrency() {
        String raw;
        try {
            raw = configService.getString("platform.payment.currency");
        } catch (IllegalStateException e) {
            raw = null;
        }
        return raw == null || raw.isBlank() ? "eur" : raw.strip().toLowerCase(Locale.ROOT);
    }
}
