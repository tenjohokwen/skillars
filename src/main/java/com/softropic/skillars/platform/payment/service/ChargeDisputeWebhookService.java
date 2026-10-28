package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
import com.softropic.skillars.platform.payment.contract.event.PaymentIntegrityAlertEvent;
import com.softropic.skillars.platform.payment.repo.BookingPayment;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import com.softropic.skillars.platform.payment.repo.ChargeDispute;
import com.softropic.skillars.platform.payment.repo.ChargeDisputeRepository;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
import com.softropic.skillars.platform.payment.repo.CoachPayoutRepository;
import com.stripe.model.Charge;
import com.stripe.model.Dispute;
import com.stripe.model.Event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * fix/payment-payouts-disputes-webhooks: card disputes (chargebacks) and refunds made outside the app,
 * from Stripe webhooks. Every charge lands on the platform account and coaches are paid later by
 * Transfer, so a chargeback costs the platform unless the coach payouts it funded are held:
 * <ul>
 *   <li>{@code charge.dispute.created} — record the dispute, freeze every not-yet-released payout the
 *       PaymentIntent funded (its booking(s) — a batch charge funds several — and the sessions of a pack
 *       it bought), alert. A payout already RELEASED is only alerted on; nothing is reversed while the
 *       dispute can still be won. A session completing later is frozen at enqueue
 *       ({@code CoachPayoutOutboxSupport}).</li>
 *   <li>{@code charge.dispute.closed} — won: lift the card-dispute freeze (the payout releases unless
 *       another freeze holds it). Lost: the coach bears their share of the disputed amount, pro rata —
 *       cut from a pending payout (cancelled when it is all of it) or reversed from a released one — and
 *       an alert.</li>
 *   <li>{@code charge.refunded} — a refund the app did not issue (e.g. from the Stripe Dashboard): an
 *       alert only, no money is moved.</li>
 * </ul>
 * Runs inside {@code StripeWebhookService.handleEventAtomically}'s transaction. Nothing here throws for a
 * charge the app does not know — a throw would roll back the idempotency row and Stripe would retry the
 * event forever — it logs and alerts instead.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChargeDisputeWebhookService {

    /** Idempotency-key prefixes of the refunds this app issues ({@code PaymentGateway.refund} callers). */
    private static final List<String> APP_REFUND_KEY_PREFIXES = List.of("refund-", "cashout-");

    private final ChargeDisputeRepository chargeDisputeRepository;
    private final CoachPayoutRepository coachPayoutRepository;
    private final BookingPaymentRepository bookingPaymentRepository;
    private final CoachPayoutAdjustmentService adjustmentService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(propagation = Propagation.MANDATORY)
    public void onDisputeCreated(Dispute dispute) {
        String pi = dispute.getPaymentIntent();
        if (pi == null || pi.isBlank()) {
            log.error("[STRIPE_CHARGE_DISPUTE] dispute={} charge={} has no payment intent — cannot match it, alerting",
                dispute.getId(), dispute.getCharge());
            alert(PaymentIntegrityAlertEvent.Kind.CHARGEBACK, refOf(dispute.getCharge(), dispute.getId()),
                "OPENED_UNMATCHED " + dispute.getId());
            return;
        }
        ChargeDispute record = chargeDisputeRepository.findByIdForUpdate(dispute.getId()).orElse(null);
        if (record != null && !ChargeDispute.STATUS_OPEN.equals(record.getStatus())) {
            // The close was delivered first (Stripe does not order events): nothing left to freeze.
            log.info("[STRIPE_CHARGE_DISPUTE] dispute={} already closed {} — created event ignored",
                dispute.getId(), record.getStatus());
            return;
        }
        if (record == null) {
            chargeDisputeRepository.save(newRecord(dispute, pi, ChargeDispute.STATUS_OPEN));
        }

        if (!isKnownIntent(pi)) {
            log.error("[STRIPE_CHARGE_DISPUTE] dispute={} on unknown payment intent {} — alerting", dispute.getId(), pi);
            alert(PaymentIntegrityAlertEvent.Kind.CHARGEBACK, pi, "OPENED_UNKNOWN_INTENT " + amountOf(dispute));
            return;
        }
        int frozen = 0;
        int released = 0;
        for (CoachPayout row : coachPayoutRepository.findFundedByPaymentIntentForUpdate(pi)) {
            if (adjustmentService.freezeForChargeback(row)) {
                frozen++;
            } else if (CoachPayoutStatus.RELEASED.equals(row.getStatus())) {
                released++;
            }
        }
        log.error("[STRIPE_CHARGE_DISPUTE] dispute={} intent={} amount={} — {} payout(s) frozen, {} already released "
            + "(not reversed automatically)", dispute.getId(), pi, amountOf(dispute), frozen, released);
        alert(PaymentIntegrityAlertEvent.Kind.CHARGEBACK, pi,
            "OPENED " + amountOf(dispute) + " frozen=" + frozen + " released=" + released);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void onDisputeClosed(Dispute dispute) {
        String pi = dispute.getPaymentIntent();
        boolean lost = "lost".equalsIgnoreCase(dispute.getStatus());
        String outcome = lost ? ChargeDispute.STATUS_LOST : ChargeDispute.STATUS_WON;
        if (pi == null || pi.isBlank()) {
            log.error("[STRIPE_CHARGE_DISPUTE] dispute={} closed {} with no payment intent — alerting",
                dispute.getId(), dispute.getStatus());
            alert(PaymentIntegrityAlertEvent.Kind.CHARGEBACK, refOf(dispute.getCharge(), dispute.getId()),
                outcome + "_UNMATCHED " + dispute.getId());
            return;
        }
        ChargeDispute record = chargeDisputeRepository.findByIdForUpdate(dispute.getId()).orElse(null);
        if (record != null && !ChargeDispute.STATUS_OPEN.equals(record.getStatus())) {
            log.info("[STRIPE_CHARGE_DISPUTE] dispute={} already closed {} — re-delivery ignored",
                dispute.getId(), record.getStatus());
            return;
        }
        if (record == null) {
            record = newRecord(dispute, pi, outcome);
        }
        record.setStatus(outcome);
        if (dispute.getAmount() != null) {
            record.setAmount(cents(dispute.getAmount()));
        }
        record.setStripeStatus(dispute.getStatus());
        record.setClosedAt(Instant.now());
        chargeDisputeRepository.saveAndFlush(record);

        if (!isKnownIntent(pi)) {
            log.error("[STRIPE_CHARGE_DISPUTE] dispute={} on unknown payment intent {} closed {} — alerting",
                dispute.getId(), pi, dispute.getStatus());
            alert(PaymentIntegrityAlertEvent.Kind.CHARGEBACK, pi, outcome + "_UNKNOWN_INTENT " + amountOf(dispute));
            return;
        }
        boolean otherOpen = chargeDisputeRepository.existsByPaymentIntentIdAndStatus(pi, ChargeDispute.STATUS_OPEN);
        BigDecimal fraction = lost ? lostFraction(pi, record.getAmount()) : BigDecimal.ZERO;
        int adjusted = 0;
        for (CoachPayout row : coachPayoutRepository.findFundedByPaymentIntentForUpdate(pi)) {
            if (lost) {
                BigDecimal refund = cardPortion(row).multiply(fraction).setScale(2, RoundingMode.HALF_UP);
                if (adjustmentService.applyRefund(row, refund, row.getGrossAmount(), "CHARGEBACK_LOST")
                        != CoachPayoutAdjustmentService.RefundOutcome.NONE) {
                    adjusted++;
                }
            }
            if (!otherOpen) {
                adjustmentService.clearChargebackFreeze(row);
            }
        }
        if (lost) {
            log.error("[STRIPE_CHARGE_DISPUTE] dispute={} intent={} LOST {} — {} coach payout(s) cut or reversed pro rata",
                dispute.getId(), pi, amountOf(dispute), adjusted);
        } else {
            log.warn("[STRIPE_CHARGE_DISPUTE] dispute={} intent={} closed {} — card-dispute freeze lifted{}",
                dispute.getId(), pi, dispute.getStatus(), otherOpen ? " (another dispute still open)" : "");
        }
        alert(PaymentIntegrityAlertEvent.Kind.CHARGEBACK, pi,
            outcome + " " + amountOf(dispute) + (lost ? " adjusted=" + adjusted : ""));
    }

    /** An app-issued refund is recognised by its idempotency key; anything else is alerted on. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onChargeRefunded(Charge charge, Event event) {
        String key = event.getRequest() == null ? null : event.getRequest().getIdempotencyKey();
        if (key != null && APP_REFUND_KEY_PREFIXES.stream().anyMatch(key::startsWith)) {
            log.debug("[STRIPE_CHARGE_REFUNDED] charge={} refunded by the app (key={})", charge.getId(), key);
            return;
        }
        String pi = charge.getPaymentIntent();
        String ref = refOf(pi, charge.getId());
        boolean known = pi != null && isKnownIntent(pi);
        log.error("[STRIPE_EXTERNAL_REFUND] charge={} intent={} refunded {} of {} outside the app (known intent={}) — "
            + "no money moved by the app, alerting", charge.getId(), pi, cents(charge.getAmountRefunded()),
            cents(charge.getAmount()), known);
        alert(PaymentIntegrityAlertEvent.Kind.EXTERNAL_REFUND, ref,
            (known ? "" : "UNKNOWN_INTENT ") + "refunded " + cents(charge.getAmountRefunded()) + " of "
                + cents(charge.getAmount()));
    }

    private boolean isKnownIntent(String pi) {
        return !chargeDisputeRepository.findBookingIdsByPaymentIntent(pi).isEmpty()
            || !chargeDisputeRepository.findPackPurchaseIdsByPaymentIntent(pi).isEmpty();
    }

    /** disputed / charged, at most 1 — a batch or pack charge disputed in part costs each payout that part. */
    private BigDecimal lostFraction(String pi, BigDecimal disputed) {
        BigDecimal charged = chargeDisputeRepository.sumChargedByPaymentIntent(pi);
        if (charged == null || charged.signum() <= 0 || disputed == null || disputed.compareTo(charged) >= 0) {
            return BigDecimal.ONE;
        }
        return disputed.divide(charged, 6, RoundingMode.HALF_UP);
    }

    /** What the disputed card charge paid of this payout's session: the pack share, or the booking's card part. */
    private BigDecimal cardPortion(CoachPayout row) {
        if (row.getSessionPackPurchaseId() != null) {
            return row.getGrossAmount();
        }
        return bookingPaymentRepository.findById(row.getBookingId())
            .map(BookingPayment::getStripeCharged)
            .orElse(BigDecimal.ZERO);
    }

    private static ChargeDispute newRecord(Dispute dispute, String pi, String status) {
        ChargeDispute r = new ChargeDispute();
        r.setStripeDisputeId(dispute.getId());
        r.setPaymentIntentId(pi);
        r.setChargeId(dispute.getCharge());
        r.setAmount(cents(dispute.getAmount()));
        r.setCurrency(dispute.getCurrency());
        r.setStatus(status);
        r.setStripeStatus(dispute.getStatus());
        r.setCreatedAt(dispute.getCreated() != null ? Instant.ofEpochSecond(dispute.getCreated()) : Instant.now());
        if (!ChargeDispute.STATUS_OPEN.equals(status)) {
            r.setClosedAt(Instant.now());
        }
        return r;
    }

    private void alert(PaymentIntegrityAlertEvent.Kind kind, String referenceId, String reason) {
        eventPublisher.publishEvent(new PaymentIntegrityAlertEvent(this, kind, refOf(referenceId, "unknown"),
            PaymentIntegrityAlertEvent.Reference.PAYMENT_INTENT, reason));
    }

    private static String refOf(String preferred, String fallback) {
        String ref = preferred != null && !preferred.isBlank() ? preferred : String.valueOf(fallback);
        return ref.length() > 36 ? ref.substring(0, 36) : ref;
    }

    private static String amountOf(Dispute d) {
        return cents(d.getAmount()) + (d.getCurrency() != null ? " " + d.getCurrency() : "");
    }

    private static BigDecimal cents(Long amount) {
        return BigDecimal.valueOf(amount == null ? 0L : amount, 2);
    }
}
