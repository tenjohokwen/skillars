package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.contract.CreditLedgerEntryType;
import com.softropic.skillars.platform.payment.contract.event.PaymentIntegrityAlertEvent;
import com.softropic.skillars.platform.payment.repo.ChargeDispute;
import com.softropic.skillars.platform.payment.repo.ChargeDisputeRepository;
import com.softropic.skillars.platform.payment.repo.ParentCreditLedger;
import com.softropic.skillars.platform.payment.repo.ParentCreditLedgerRepository;
import com.softropic.skillars.platform.payment.repo.ParentCreditLedgerRepository.TracedCredit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * fix/payment-dispute-credit-trace-and-lost-dispute: wallet credit funded by a card charge that is later
 * disputed. A parent pays by card, the booking is refunded to wallet credit, then the parent files a
 * chargeback on the original charge: without this they would get the money back twice (the bank refund
 * and the credit, which they can spend or cash out).
 * <ul>
 *   <li><b>Dispute opened</b> — the credit the charge funded is traced
 *       ({@link ParentCreditLedgerRepository#findCreditFundedByPaymentIntent}) and up to the disputed amount
 *       of it is taken back ({@code CHARGEBACK_CREDIT_REVERSAL}), capped at each payer's balance, never
 *       below zero. What was already spent or cashed out is a shortfall the platform absorbs, raised as a
 *       {@code CHARGEBACK} admin alert. Cash-out is refused while the dispute is open
 *       ({@link CreditWalletService#debitForCashOut}).</li>
 *   <li><b>Won</b> — the platform keeps the charge, so the reversed credit is given back
 *       ({@code CHARGEBACK_CREDIT_RESTORE}).</li>
 *   <li><b>Lost</b> — the reversal stands; credit the charge funded after the dispute opened is taken back
 *       too (a top-up), and so is credit written after the close ({@link #onRefundCredited}).</li>
 * </ul>
 * Every write runs under the {@code charge_disputes} row lock the caller holds, and debits only the
 * difference between the new target and {@link ChargeDispute#getCreditReversalTarget()} (stored in the same
 * transaction), so a re-delivered event debits nothing more. Each debit has its own reference — opened,
 * lost top-up, or late credit for one booking — and V199 allows one reversal and one restore per
 * (reference, payer). Lock order: dispute row, then the payer's wallet lock — the same as the webhook.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChargebackCreditReversalService {

    private final ParentCreditLedgerRepository ledgerRepository;
    private final ChargeDisputeRepository chargeDisputeRepository;
    private final CreditWalletService creditWalletService;
    private final ApplicationEventPublisher eventPublisher;

    /** What a reversal pass found and did. */
    public record Outcome(BigDecimal traced, BigDecimal target, BigDecimal recovered, BigDecimal shortfall) {
        static final Outcome NONE = new Outcome(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** The ledger reference of the reversal made when the dispute opened (and of its restore). */
    public static UUID reference(String disputeId) {
        return nameUuid("chargeback-credit:" + disputeId);
    }

    /** The reference of the top-up made when the dispute closed lost. */
    static UUID lostTopUpReference(String disputeId) {
        return nameUuid("chargeback-credit-lost:" + disputeId);
    }

    /** The reference of a reversal of credit written for one booking after the dispute was lost. */
    static UUID lateReference(String disputeId, UUID bookingId) {
        return nameUuid("chargeback-credit-late:" + disputeId + ":" + bookingId);
    }

    /** The credit traced to the dispute's charge, in total. */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal tracedCredit(String paymentIntentId) {
        return sum(ledgerRepository.findCreditFundedByPaymentIntent(paymentIntentId));
    }

    /**
     * Dispute opened (or closed lost before an open was seen): takes back the traced credit, up to the
     * disputed amount. A no-op when the dispute was already traced.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome reverseOnOpen(ChargeDispute dispute) {
        if (dispute.getCreditReversalTarget() != null) {
            return Outcome.NONE;
        }
        return reverse(dispute, reference(dispute.getStripeDisputeId()), "OPENED");
    }

    /**
     * Dispute closed lost: the reversal stands. Credit funded by the charge since the dispute opened is taken
     * back too, and a dispute never traced (close delivered first) is traced now.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome reverseOnLost(ChargeDispute dispute) {
        if (dispute.getCreditReversalTarget() == null) {
            return reverse(dispute, reference(dispute.getStripeDisputeId()), "LOST");
        }
        return reverse(dispute, lostTopUpReference(dispute.getStripeDisputeId()), "LOST");
    }

    /**
     * Dispute closed won: gives back every reversal made when it opened.
     *
     * @return the total restored
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal restoreOnWon(ChargeDispute dispute) {
        UUID reference = reference(dispute.getStripeDisputeId());
        BigDecimal restored = BigDecimal.ZERO;
        for (ParentCreditLedger reversal : ledgerRepository.findByReferenceIdAndTypeOrderByParentId(
                reference, CreditLedgerEntryType.CHARGEBACK_CREDIT_REVERSAL)) {
            BigDecimal amount = reversal.getAmount().negate();
            if (creditWalletService.restoreChargebackCredit(reversal.getParentId(), reference, amount)) {
                restored = restored.add(amount);
            }
        }
        if (restored.signum() > 0) {
            log.warn("[CHARGEBACK_CREDIT] dispute={} won — {} of reversed wallet credit restored",
                dispute.getStripeDisputeId(), restored);
        }
        return restored;
    }

    /**
     * A {@code BOOKING_REFUND} was just credited for {@code bookingId} (the credit-wallet refund outbox
     * handler). When the charge that paid for it already lost a card dispute, the payer has been refunded by
     * their bank, so the new credit is taken back now. An open dispute takes it back if lost
     * ({@link #reverseOnLost}); cash-out is refused meanwhile.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onRefundCredited(UUID bookingId, String sourcePaymentIntentId) {
        List<String> intents = sourcePaymentIntentId != null
            ? List.of(sourcePaymentIntentId)
            : chargeDisputeRepository.findFundingPaymentIntents(bookingId);
        if (intents.isEmpty()) {
            return;
        }
        for (ChargeDispute dispute : chargeDisputeRepository.findByPaymentIntentsAndStatusesForUpdate(
                intents, List.of(ChargeDispute.STATUS_OPEN, ChargeDispute.STATUS_LOST))) {
            if (ChargeDispute.STATUS_OPEN.equals(dispute.getStatus())) {
                log.warn("[CHARGEBACK_CREDIT] booking={} refunded to credit while dispute {} is open on {} — taken "
                    + "back if the dispute is lost", bookingId, dispute.getStripeDisputeId(), dispute.getPaymentIntentId());
                continue;
            }
            reverse(dispute, lateReference(dispute.getStripeDisputeId(), bookingId), "LATE");
            chargeDisputeRepository.save(dispute);
        }
    }

    /**
     * Takes back {@code min(traced, disputed) - already targeted}, spread over the payers in id order (one,
     * in practice: a charge has one payer), each capped at their balance; records the new target on the
     * dispute (the caller saves it) and alerts on a shortfall.
     */
    private Outcome reverse(ChargeDispute dispute, UUID reference, String phase) {
        List<TracedCredit> traced = ledgerRepository.findCreditFundedByPaymentIntent(dispute.getPaymentIntentId());
        BigDecimal total = sum(traced);
        BigDecimal disputed = dispute.getAmount() == null ? BigDecimal.ZERO : dispute.getAmount();
        BigDecimal already = dispute.getCreditReversalTarget() == null ? BigDecimal.ZERO : dispute.getCreditReversalTarget();
        BigDecimal target = total.min(disputed).max(already);
        dispute.setCreditReversalTarget(target);
        BigDecimal extra = target.subtract(already);
        if (extra.signum() <= 0) {
            return new Outcome(total, target, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        // Skip what earlier passes already targeted, payer by payer, then take the rest.
        BigDecimal skip = already;
        BigDecimal remaining = extra;
        BigDecimal recovered = BigDecimal.ZERO;
        String description = "Card dispute " + dispute.getStripeDisputeId()
            + " on a charge already returned as credit — credit reversed";
        for (TracedCredit credit : traced) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal available = credit.getAmount();
            BigDecimal skipped = available.min(skip);
            skip = skip.subtract(skipped);
            BigDecimal take = available.subtract(skipped).min(remaining);
            if (take.signum() <= 0) {
                continue;
            }
            remaining = remaining.subtract(take);
            recovered = recovered.add(
                creditWalletService.debitChargebackCredit(credit.getParentId(), reference, take, description));
        }
        BigDecimal shortfall = extra.subtract(recovered).max(BigDecimal.ZERO);
        log.warn("[CHARGEBACK_CREDIT] dispute={} intent={} {} — credit traced {}, targeted {} (+{}), recovered {}, "
                + "shortfall {}", dispute.getStripeDisputeId(), dispute.getPaymentIntentId(), phase, total, target,
            extra, recovered, shortfall);
        if (shortfall.signum() > 0) {
            // Already spent or cashed out: the platform absorbs it. One alert per dispute (the reference).
            eventPublisher.publishEvent(new PaymentIntegrityAlertEvent(this, PaymentIntegrityAlertEvent.Kind.CHARGEBACK,
                refOf(dispute.getStripeDisputeId()), PaymentIntegrityAlertEvent.Reference.PAYMENT_INTENT,
                "CREDIT_SHORTFALL " + phase + " " + shortfall + " of " + extra));
        }
        return new Outcome(total, target, recovered, shortfall);
    }

    private static BigDecimal sum(List<TracedCredit> traced) {
        return traced.stream().map(TracedCredit::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static UUID nameUuid(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    private static String refOf(String id) {
        return id.length() > 36 ? id.substring(0, 36) : id;
    }
}
