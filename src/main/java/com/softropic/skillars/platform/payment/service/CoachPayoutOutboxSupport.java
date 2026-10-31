package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.booking.contract.CoachNoShowEvent;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.outbox.service.OutboxService;
import com.softropic.skillars.platform.payment.contract.CoachPayoutReversalPayload;
import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
import com.softropic.skillars.platform.payment.contract.CoachPayoutTopUpPayload;
import com.softropic.skillars.platform.payment.contract.CoachPayoutTransferPayload;
import com.softropic.skillars.platform.payment.repo.BookingPayment;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import com.softropic.skillars.platform.payment.repo.ChargeDispute;
import com.softropic.skillars.platform.payment.repo.ChargeDisputeRepository;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
import com.softropic.skillars.platform.payment.repo.CoachPayoutFreezeRepository;
import com.softropic.skillars.platform.payment.repo.CoachPayoutRepository;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccount;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccountRepository;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchase;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
    public static final String AGGREGATE_TYPE_TOPUP = "COACH_PAYOUT_TOPUP";

    /** AC10.4 bound: 14 days = disputes.submissionWindowDays, the longest sane hold. */
    private static final long HOLD_HOURS_DEFAULT = 48L;
    private static final long HOLD_HOURS_MIN = 0L;
    private static final long HOLD_HOURS_MAX = 336L;

    static final String REASON_NOTHING_CHARGED = "NOTHING_CHARGED";
    static final String REASON_PACK_AMOUNT_UNKNOWN = "PACK_AMOUNT_UNKNOWN";
    static final String REASON_PACK_FULLY_ALLOCATED = "PACK_FULLY_ALLOCATED";
    static final String REASON_CHARGEBACK_LOST = "CHARGEBACK_LOST";

    private final CoachPayoutRepository coachPayoutRepository;
    private final BookingPaymentRepository bookingPaymentRepository;
    private final BookingRepository bookingRepository;
    private final SessionPackPurchaseRepository sessionPackPurchaseRepository;
    private final CoachPayoutFreezeRepository coachPayoutFreezeRepository;
    private final PessimisticLockRetryer lockRetryer;
    private final CoachStripeAccountRepository coachStripeAccountRepository;
    private final OutboxService outboxService;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;
    private final ChargeDisputeRepository chargeDisputeRepository;

    /**
     * AC3.2 / AC4.1: insert the {@code coach_payouts} row {@code PENDING_RELEASE} and enqueue a
     * {@code COACH_PAYOUT_TRANSFER} message, both in the caller's (completion) transaction.
     *
     * <p>Idempotent by the {@code booking_id} PK: a duplicate {@code BookingCompletedEvent} that
     * arrives before the first transaction committed finds the row already present and is a no-op
     * (the handler is idempotent regardless — AC7.1/7.2). Two concurrent first deliveries both insert;
     * the loser fails on {@code coach_payouts_pkey} and its whole transaction — including any pack
     * allocation below — rolls back, so a pack is never allocated twice for one booking.
     *
     * <p>feat/coach-payouts-pack-and-credit (DECISIONS.md item 3): what the coach is paid for depends
     * on how the session was funded, and every funding source now pays through this one row:
     * <ul>
     *   <li><b>card and/or wallet credit</b> — gross = {@code stripe_charged + credit_debited} (wallet
     *       credit is money the platform already holds from an earlier card charge), at the commission
     *       rate stamped at capture. A mixed booking is ONE row and ONE transfer, so the card and credit
     *       parts can never be paid twice;</li>
     *   <li><b>session pack</b> — gross = this session's share of what the pack was charged
     *       ({@link #allocatePackShare}), at the commission rate stamped at pack purchase.</li>
     * </ul>
     * Unused sessions of an expired pack never complete, so they never reach this method — the
     * platform keeps that money.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueuePayout(UUID bookingId, UUID coachId) {
        enqueuePayoutInternal(bookingId, coachId, false, null);
    }

    /**
     * S-07 conflict zone (option B): the coach quick-completed WITHOUT a live Start and the booker reports a coach
     * no-show, so neither side is established. The session fee is split: the payer's half is returned, the coach is
     * paid the rest through the ordinary payout row — commission at the rate locked at capture / pack purchase on
     * the coach's half only, the same release hold (new-coach hold included), the same freeze / dispute handling.
     *
     * <p>Rounding: the payer's half is {@code floor(paid / 2)} to the cent, the coach's gross is the remainder (so
     * the coach takes an odd cent) and the commission is taken from that. A pack session settles ONE ledger slot
     * ({@link SessionPackPurchase#recordSplitSettlement}), so the pack's payouts + refunds still sum to exactly what
     * it was charged.
     *
     * @param paidFallback what the payer paid per {@code BookingService.resolveAmountPaid}; used for the refund only
     *                     when there is no captured payment row to pay the coach from (a row-less legacy booking)
     * @return the amount to refund to the payer — zero when nothing was charged, the coach's row already exists, or
     *     a pack session could not be allocated (logged; an operator follows up)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal enqueueSplitPayout(UUID bookingId, UUID coachId, BigDecimal paidFallback) {
        return enqueuePayoutInternal(bookingId, coachId, true, paidFallback);
    }

    private BigDecimal enqueuePayoutInternal(UUID bookingId, UUID coachId, boolean split, BigDecimal paidFallback) {
        BigDecimal refundShare = BigDecimal.ZERO;
        if (coachPayoutRepository.existsById(bookingId)) {
            log.info("[COACH_PAYOUT] coach_payouts row already exists for booking {} — enqueue is a no-op", bookingId);
            return refundShare;
        }

        String currency = resolveCurrency();
        UUID packId = bookingRepository.findById(bookingId).map(Booking::getSessionPackPurchaseId).orElse(null);

        final BigDecimal gross;
        final BigDecimal rate;
        final BigDecimal cardPortion;
        if (packId != null) {
            PackShare share = allocatePackShare(bookingId, packId, split);
            if (share.alreadyPaidOut()) {
                return refundShare;
            }
            if (share.skipReason() != null) {
                CoachPayout row = newRow(bookingId, coachId, currency, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, Instant.now());
                row.setSessionPackPurchaseId(packId);
                row.setStatus(CoachPayoutStatus.CANCELLED);
                row.setLastError(share.skipReason());
                coachPayoutRepository.save(row);
                return refundShare;
            }
            gross = share.gross();
            refundShare = share.refundShare();
            cardPortion = gross;
            rate = share.commissionRate() != null ? share.commissionRate() : liveCommissionRate();
        } else {
            BookingPayment bp = bookingPaymentRepository.findById(bookingId).orElse(null);
            if (bp == null) {
                // A completed booking with no booking_payments row never went through a charge path.
                // Nothing to pay the coach from; coach earnings for such bookings are out of scope
                // (design doc §0.1).
                log.warn("[COACH_PAYOUT] no booking_payments row for completed booking {} — no payout enqueued", bookingId);
                // A split still returns the payer's half: it is owed whether or not the coach's half can be paid.
                return split ? CoachNoShowEvent.splitRefundShare(paidFallback) : refundShare;
            }
            BigDecimal stripeCharged = bp.getStripeCharged() == null ? BigDecimal.ZERO : bp.getStripeCharged();
            BigDecimal creditDebited = bp.getCreditDebited() == null ? BigDecimal.ZERO : bp.getCreditDebited();
            BigDecimal totalPaid = stripeCharged.add(creditDebited);
            if (split && totalPaid.signum() > 0) {
                refundShare = CoachNoShowEvent.splitRefundShare(totalPaid);
                gross = totalPaid.subtract(refundShare);
                cardPortion = stripeCharged.multiply(gross).divide(totalPaid, 2, RoundingMode.HALF_UP);
            } else {
                gross = totalPaid;
                cardPortion = stripeCharged;
            }
            // AC4.2: the rate LOCKED AT CAPTURE (booking_payments.commission_rate), never
            // platform.commission.rate re-read now.
            rate = bp.getCommissionRate() != null ? bp.getCommissionRate() : liveCommissionRate();
        }

        if (gross.signum() <= 0) {
            // Nothing was paid for this session — no transfer. Write a directly-CANCELLED row
            // (reason NOTHING_CHARGED) for auditability; do NOT enqueue.
            CoachPayout row = newRow(bookingId, coachId, currency, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                Instant.now());
            row.setSessionPackPurchaseId(packId);
            row.setStatus(CoachPayoutStatus.CANCELLED);
            row.setLastError(REASON_NOTHING_CHARGED);
            coachPayoutRepository.save(row);
            log.info("[COACH_PAYOUT] booking {} had nothing charged — coach_payouts row written CANCELLED "
                + "({}), no transfer", bookingId, REASON_NOTHING_CHARGED);
            return refundShare;
        }

        BigDecimal commission = gross.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal net = gross.subtract(commission);

        // fix/audit3-wallet-cashout-integrity: a new coach's payout is held longer (see resolveHold).
        PayoutHold hold = resolveHold(configService, coachPayoutRepository, coachId);
        Instant releaseAfter = Instant.now().plus(Duration.ofHours(hold.appliedHoldHours()));

        CoachPayout row = newRow(bookingId, coachId, currency, gross, commission, net, releaseAfter);
        row.setSessionPackPurchaseId(packId);
        // fix/payment-payouts-disputes-webhooks: a dispute opened before the session completed — in-app
        // (open on this booking) or a card dispute on the charge that funded it — freezes the payout from
        // the start.
        List<String> intents = chargeDisputeRepository.findFundingPaymentIntents(bookingId);
        List<String> chargeDisputeStatuses = intents.isEmpty()
            ? List.of() : chargeDisputeRepository.findStatusesByPaymentIntentIds(intents);
        // fix/payment-dispute-credit-trace-and-lost-dispute: a card dispute already LOST — the session
        // completed after the payer's bank took the money back. This payout used to be parked in HOLD for an
        // operator (and could sit there forever); it is now cut exactly as the lost close cuts a payout that
        // already existed (the dispute's payout_loss_fraction of the card portion), or cancelled when that
        // is all of it.
        if (chargeDisputeStatuses.contains(ChargeDispute.STATUS_LOST)) {
            BigDecimal fraction = lostFraction(intents);
            BigDecimal refund = cardPortion.multiply(fraction).setScale(2, RoundingMode.HALF_UP);
            BigDecimal lostShare = CoachPayoutAdjustmentService.coachShareOfRefund(net, refund, gross);
            if (lostShare.compareTo(net) >= 0) {
                row.setStatus(CoachPayoutStatus.CANCELLED);
                row.setLastError(REASON_CHARGEBACK_LOST);
                row.setCoachStripeAccountId(resolveCoachAccountIdBestEffort(coachId));
                coachPayoutRepository.save(row);
                log.warn("[COACH_PAYOUT_CHARGEBACK_LOST] booking={} coach={} — the charge that paid for this session "
                    + "lost a card dispute for all of it, payout written CANCELLED, no transfer", bookingId, coachId);
                return refundShare;
            }
            if (lostShare.signum() > 0) {
                BigDecimal newNet = CoachPayoutAdjustmentService.cutNet(row, lostShare);
                row.setLastError(REASON_CHARGEBACK_LOST + " -" + lostShare + " (net was " + net + ")");
                log.warn("[COACH_PAYOUT_CHARGEBACK_LOST] booking={} coach={} — the charge that paid for this session "
                    + "lost a card dispute in part, payout cut by {} to {}", bookingId, coachId, lostShare, newNet);
            }
        }
        row.setDisputeFrozen(coachPayoutRepository.existsOpenDisputeForBooking(bookingId));
        row.setChargebackFrozen(chargeDisputeStatuses.contains(ChargeDispute.STATUS_OPEN));
        // A coach already under a CSAM safety hold: record the payout FROZEN, never PENDING_RELEASE.
        // (A hold landing after this read is caught by CoachPayoutTransferHandler's release-time check.)
        boolean safetyHold = coachPayoutFreezeRepository.isFrozenForShare(coachId);
        boolean frozen = safetyHold || row.hasPayoutFreezeReason();
        if (frozen) {
            row.setStatus(CoachPayoutStatus.FROZEN);
            row.setLastError(safetyHold ? CoachPayoutFreezeService.REASON_SAFETY_HOLD
                : row.isDisputeFrozen() ? CoachPayoutAdjustmentService.REASON_DISPUTE_OPEN
                : CoachPayoutAdjustmentService.REASON_CHARGEBACK_OPEN);
        } else {
            row.setStatus(CoachPayoutStatus.PENDING_RELEASE);
        }
        row.setCoachStripeAccountId(resolveCoachAccountIdBestEffort(coachId));
        coachPayoutRepository.save(row);

        if (frozen) {
            log.warn("[COACH_PAYOUT_FROZEN] booking={} coach={} net={} reason={} — payout recorded FROZEN, no transfer "
                + "until the freeze is lifted", bookingId, coachId, row.getNetAmount(), row.getLastError());
            return refundShare;
        }
        enqueue(AGGREGATE_TYPE_TRANSFER, new CoachPayoutTransferPayload(bookingId), releaseAfter, bookingId);
        log.info("[COACH_PAYOUT] enqueued transfer for booking {} coach {} gross {} net {} pack {} releaseAfter {}",
            bookingId, coachId, row.getGrossAmount(), row.getNetAmount(), packId, releaseAfter);
        return refundShare;
    }

    /**
     * fix/audit3-wallet-cashout-integrity: the payout hold for one coach, and why. The normal hold
     * ({@code payment.payout.hold_hours}, 48h) is shorter than the window in which a payer's bank can still
     * charge a card payment back, so a coach paid out at once and then gone left the platform to absorb every
     * chargeback. A coach with fewer than {@code payment.payout.newCoach.releasedCount} (5) payouts ever
     * transferred is held {@code payment.payout.newCoach.holdHours} (168h) instead — never less than the normal
     * hold. Only RELEASED payouts with a net of at least {@code payment.payout.newCoach.minReleasedNet} (20) count,
     * so a few tiny sessions cannot end it. Read when the payout row is written, so a payout keeps the hold it was enqueued with. Static so the
     * coach revenue page ({@code RevenueReportingService}) shows exactly the rule applied here.
     *
     * @param normalHoldHours     {@code payment.payout.hold_hours}
     * @param newCoachHoldHours   {@code payment.payout.newCoach.holdHours}
     * @param newCoachPayoutCount {@code payment.payout.newCoach.releasedCount} — how many first payouts are held longer
     * @param releasedCount       the coach's qualifying payouts released so far — status RELEASED with a net of at
     *                            least {@code payment.payout.newCoach.minReleasedNet}
     * @param minReleasedNet      {@code payment.payout.newCoach.minReleasedNet}, in the platform currency
     */
    public record PayoutHold(long normalHoldHours, long newCoachHoldHours, long newCoachPayoutCount,
                             long releasedCount, long minReleasedNet) {
        public PayoutHold(long normalHoldHours, long newCoachHoldHours, long newCoachPayoutCount, long releasedCount) {
            this(normalHoldHours, newCoachHoldHours, newCoachPayoutCount, releasedCount, 0L);
        }

        /** True while the coach still gets the longer new-coach hold. */
        public boolean newCoach() {
            return releasedCount < newCoachPayoutCount && newCoachHoldHours > normalHoldHours;
        }

        /** How many of the first payouts are still to be transferred (0 once past them). */
        public long newCoachPayoutsRemaining() {
            return newCoach() ? newCoachPayoutCount - releasedCount : 0L;
        }

        public long appliedHoldHours() {
            return newCoach() ? newCoachHoldHours : normalHoldHours;
        }
    }

    public static PayoutHold resolveHold(ConfigService configService, CoachPayoutRepository coachPayoutRepository,
                                         UUID coachId) {
        long normal = configService.getBoundedLong(
            "payment.payout.hold_hours", HOLD_HOURS_DEFAULT, HOLD_HOURS_MIN, HOLD_HOURS_MAX);
        long newCoachCount = configService.getBoundedLong(ConfigBounds.PAYOUT_NEW_COACH_RELEASED_COUNT);
        long newCoachHours = configService.getBoundedLong(ConfigBounds.PAYOUT_NEW_COACH_HOLD_HOURS);
        long minNet = configService.getBoundedLong(ConfigBounds.PAYOUT_NEW_COACH_MIN_RELEASED_NET);
        long released = newCoachCount > 0
            ? coachPayoutRepository.countQualifyingReleasedByCoach(coachId, BigDecimal.valueOf(minNet)) : 0L;
        return new PayoutHold(normal, newCoachHours, newCoachCount, released, minNet);
    }

    /**
     * This session's share of its pack, allocated under the pack row lock so concurrent completions of
     * sessions from the same pack serialize: each takes {@code amount_charged / session_count} rounded
     * DOWN to the cent, the last ({@code session_count}-th) SETTLED session takes whatever remains, and
     * the running total can therefore never exceed {@code amount_charged}. fix/payment-pack-refund-rounding:
     * "settled" counts sessions refunded to the payer as money too ({@link PackRefundAllocationService}),
     * so payouts + refunds of a fully-settled pack sum to exactly {@code amount_charged}
     * ({@link SessionPackPurchase#nextSettlementValue}).
     *
     * <p>Lock order: the completion transaction already holds this booking's row; the pack row comes
     * second. The other pack-row lockers ({@code PackSessionService.deductSession} / {@code pausePack},
     * {@code SessionPackPaymentService.extendPack}) never hold a <em>completing</em> booking's row, so
     * there is no cycle; NOWAIT + {@link PessimisticLockRetryer} bounds any wait, and an exhausted retry
     * rolls the completion back to its caller (retryable) rather than losing the payout.
     */
    private PackShare allocatePackShare(UUID bookingId, UUID packId, boolean split) {
        SessionPackPurchase pack = lockRetryer.withBoundedRetry("CoachPayoutOutboxSupport.allocatePackShare",
            () -> sessionPackPurchaseRepository.findByIdForUpdate(packId).orElse(null));
        // Re-check under the pack lock: a concurrent first delivery for the same booking that committed
        // while this one waited for the lock is visible now (READ COMMITTED). Without this, save()'s
        // merge would overwrite that row and the pack would be allocated twice for one session.
        if (coachPayoutRepository.existsById(bookingId)) {
            log.info("[COACH_PAYOUT] coach_payouts row for booking {} committed while waiting for the pack lock "
                + "— enqueue is a no-op", bookingId);
            return PackShare.ALREADY_PAID_OUT;
        }
        if (pack == null) {
            log.error("[COACH_PAYOUT_PACK] booking={} references session pack {} which does not exist — no payout",
                bookingId, packId);
            return PackShare.skip(REASON_PACK_AMOUNT_UNKNOWN);
        }
        BigDecimal amount = pack.getAmountCharged();
        Integer sessions = pack.getSessionCount();
        if (amount == null || sessions == null || sessions <= 0) {
            // Only a pre-V181/V186 row whose tier could not be joined: what the pack was charged per
            // session is unknown, so nothing is paid automatically — paying a guessed amount could
            // exceed what was charged. CANCELLED + ERROR; an operator pays it by hand if warranted.
            log.error("[COACH_PAYOUT_PACK] booking={} pack={} has no stamped amount_charged/session_count "
                + "(amount={} sessions={}) — payout NOT computed, operator follow-up", bookingId, packId, amount, sessions);
            return PackShare.skip(REASON_PACK_AMOUNT_UNKNOWN);
        }
        // fix/payment-pack-refund-rounding: payouts and money refunds settle the pack's sessions from one
        // ledger, so the LAST session settled (paid or refunded) takes the rounding remainder.
        BigDecimal remaining = pack.unsettledValue();
        int settledSessions = pack.settledSessions();
        if (settledSessions >= sessions || remaining.signum() <= 0) {
            // More settled sessions than the pack sold (should be impossible — deductSession refuses an
            // exhausted pack, a refunded session never completes): the pack's money is already fully
            // settled. Never pay beyond it.
            log.error("[COACH_PAYOUT_PACK] booking={} pack={} is already fully settled ({} paid + {} refunded of {} "
                + "sessions, {} of {}) — no payout for this session, investigate", bookingId, packId,
                pack.getPayoutSessionsAllocated(), pack.getRefundSessionsIssued(), sessions, pack.settledGross(), amount);
            return PackShare.skip(REASON_PACK_FULLY_ALLOCATED);
        }
        BigDecimal gross = pack.nextSettlementValue();
        if (split) {
            // S-07: one slot, two settlements — the coach's half is allocated, the payer's half is refunded by the caller.
            BigDecimal payerHalf = CoachNoShowEvent.splitRefundShare(gross);
            BigDecimal coachHalf = gross.subtract(payerHalf);
            pack.recordSplitSettlement(coachHalf, payerHalf);
            sessionPackPurchaseRepository.save(pack);
            return new PackShare(coachHalf, pack.getCommissionRate(), null, payerHalf);
        }
        pack.recordPayoutAllocation(gross);
        sessionPackPurchaseRepository.save(pack);
        return new PackShare(gross, pack.getCommissionRate(), null, BigDecimal.ZERO);
    }

    /**
     * fix/payment-pack-clawback-and-expired-cancel: whether a zero-net CANCELLED payout may be deleted and
     * computed afresh by {@link #enqueuePayout}. A card / credit payout always can (its gross is re-read from
     * {@code booking_payments}, nothing else is written). A pack payout only when it was a skip
     * ({@code PACK_AMOUNT_UNKNOWN} / {@code PACK_FULLY_ALLOCATED}): those never allocated the session's share.
     * Any other pack row (e.g. {@code NOTHING_CHARGED}, a 0.00 share) has already been settled on the pack,
     * and recomputing it would settle the session twice.
     */
    static boolean isRecomputable(CoachPayout row) {
        if (row.getSessionPackPurchaseId() == null) {
            return true;
        }
        return REASON_PACK_AMOUNT_UNKNOWN.equals(row.getLastError())
            || REASON_PACK_FULLY_ALLOCATED.equals(row.getLastError());
    }

    /** A pack session's gross share and the pack's stamped rate, or why nothing is paid. */
    private record PackShare(BigDecimal gross, BigDecimal commissionRate, String skipReason, BigDecimal refundShare) {
        static final PackShare ALREADY_PAID_OUT = new PackShare(BigDecimal.ZERO, null, "ALREADY_PAID_OUT", BigDecimal.ZERO);

        static PackShare skip(String reason) {
            return new PackShare(BigDecimal.ZERO, null, reason, BigDecimal.ZERO);
        }

        boolean alreadyPaidOut() {
            return this == ALREADY_PAID_OUT;
        }
    }

    /**
     * feat/coach-payouts-pack-and-credit: re-enqueue the transfer of a payout that has just been put
     * back to {@code PENDING_RELEASE} (a safety-hold freeze lifted). Not before {@code notBefore}, so a
     * payout unfrozen inside its original hold window still waits it out.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueTransfer(UUID bookingId, Instant notBefore) {
        enqueue(AGGREGATE_TYPE_TRANSFER, new CoachPayoutTransferPayload(bookingId), notBefore, bookingId);
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

    /**
     * fix/payment-payout-recovery: an {@code UPHOLD_COACH} on a payout that released short of the coach's
     * full net — enqueue a {@code COACH_PAYOUT_TOPUP} transfer of the shortfall, in the dispute-resolution
     * transaction. {@code CoachPayoutTopUpHandler} pays it as its own Stripe transfer (the
     * one-transfer-per-booking key {@code transfer-{bookingId}} is already spent).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueTopUp(UUID bookingId, BigDecimal amount) {
        UUID topUpId = UUID.randomUUID();
        enqueue(AGGREGATE_TYPE_TOPUP, new CoachPayoutTopUpPayload(bookingId, amount, topUpId), null, bookingId);
        log.info("[COACH_PAYOUT_TOPUP] enqueued top-up for booking {} amount {} topUpId {}", bookingId, amount, topUpId);
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

    /**
     * The share of the card portion lost to the LOST card disputes on {@code intents}, at most 1: each
     * dispute's recorded {@code payout_loss_fraction}, or — closed before V199 recorded it — disputed /
     * charged.
     */
    private BigDecimal lostFraction(List<String> intents) {
        BigDecimal total = BigDecimal.ZERO;
        for (ChargeDispute d : chargeDisputeRepository.findByPaymentIntentIdInAndStatus(intents, ChargeDispute.STATUS_LOST)) {
            BigDecimal f = d.getPayoutLossFraction();
            if (f == null) {
                BigDecimal charged = chargeDisputeRepository.sumChargedByPaymentIntent(d.getPaymentIntentId());
                BigDecimal disputed = d.getAmount() == null ? BigDecimal.ZERO : d.getAmount();
                f = charged == null || charged.signum() <= 0 || disputed.compareTo(charged) >= 0
                    ? BigDecimal.ONE : disputed.divide(charged, 6, RoundingMode.HALF_UP);
            }
            total = total.add(f);
        }
        return total.min(BigDecimal.ONE);
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
        // fix/payment-payout-recovery: stamped before any cut (the chargeback-lost cut below runs after this),
        // so refund shares and an UPHOLD_COACH restore work from what the coach was first owed.
        row.setOriginalGrossAmount(gross);
        row.setOriginalCommissionAmount(commission);
        row.setOriginalNetAmount(net);
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
