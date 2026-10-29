package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchase;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * fix/payment-pack-refund-rounding: the money value of a pack session refunded to the payer.
 *
 * <p>A pack's sessions settle one of two ways — paid to the coach ({@code CoachPayoutOutboxSupport}) or
 * refunded to the payer as wallet credit (a cancellation / coach no-show / admin cancellation / pause of a
 * booking on a pack that has since expired, when restoring the session would be worthless). Each settles at
 * {@link SessionPackPurchase#perSessionShare()} (rounded DOWN) except the LAST, which takes what is left of
 * {@code amount_charged}. Before this, refunds always used the rounded-down share and never counted towards
 * the payout's "last session", so up to {@code session_count - 1} cents of every pack reached neither the
 * coach nor the payer. Now payouts + refunds of a fully-settled pack sum to exactly {@code amount_charged};
 * an unused session of an expired pack (never settled) is the platform's, as before.
 *
 * <p>Both methods run in the caller's transaction ({@code MANDATORY} — never {@code REQUIRES_NEW}: the
 * caller may already hold this pack's row lock) and take the pack row lock NOWAIT with a bounded retry.
 * Lock order is the completion path's: the booking row (held by the cancellation) first, the pack second.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PackRefundAllocationService {

    private final SessionPackPurchaseRepository sessionPackPurchaseRepository;
    private final PessimisticLockRetryer lockRetryer;

    /**
     * Settles {@code bookingId}'s pack session as a money refund and returns its amount — the per-session
     * share, or the pack's unsettled remainder when this is the last session to settle. Idempotent per
     * booking: a second call returns the amount the first one recorded.
     *
     * @param fallback what to refund when the pack row is gone (the amount the cancellation computed)
     * @return the amount to credit; zero when the pack is already fully settled, or the session's share
     *     rounds to nothing (nothing to credit either way)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal allocateSessionRefund(UUID packId, UUID bookingId, BigDecimal fallback) {
        SessionPackPurchase pack = lockRetryer.withBoundedRetry("PackRefundAllocationService.allocate",
            () -> sessionPackPurchaseRepository.findByIdForUpdate(packId).orElse(null));
        if (pack == null) {
            log.error("[PACK_REFUND] booking={} references pack {} which does not exist — refunding the computed {} "
                + "untracked, reconcile by hand", bookingId, packId, fallback);
            return fallback == null ? BigDecimal.ZERO : fallback;
        }
        // Read under the lock: a concurrent refund of the same booking that committed while this one waited
        // is visible now (READ COMMITTED).
        Optional<BigDecimal> already = sessionPackPurchaseRepository.findRefundMarkerAmount(bookingId);
        if (already.isPresent()) {
            log.info("[PACK_REFUND] booking={} pack={} already refunded {} — returning the recorded amount",
                bookingId, packId, already.get());
            return already.get();
        }
        if (pack.hasStampedAmount() && pack.settledSessions() >= pack.getSessionCount()) {
            log.error("[PACK_REFUND] booking={} pack={} is already fully settled ({} paid + {} refunded of {} sessions, "
                + "{} of {}) — nothing refunded, investigate", bookingId, packId, pack.getPayoutSessionsAllocated(),
                pack.getRefundSessionsIssued(), pack.getSessionCount(), pack.settledGross(), pack.getAmountCharged());
            return BigDecimal.ZERO;
        }
        // Recorded even when it is 0.00 (a pack charged less than a cent a session): the session is still
        // settled, so the pack's last session goes on taking the remainder.
        BigDecimal amount = pack.nextSettlementValue();
        if (amount == null || amount.signum() < 0) {
            amount = BigDecimal.ZERO;
        }
        sessionPackPurchaseRepository.insertRefundMarker(bookingId, packId, amount);
        pack.recordRefund(amount);
        sessionPackPurchaseRepository.save(pack);
        log.info("[PACK_REFUND] booking={} pack={} refunded {} ({} paid + {} refunded of {} sessions settled, {} of {})",
            bookingId, packId, amount, pack.getPayoutSessionsAllocated(), pack.getRefundSessionsIssued(),
            pack.getSessionCount(), pack.settledGross(), pack.getAmountCharged());
        return amount;
    }

    /**
     * Un-settles {@code bookingId}'s refunded pack session after its refund was clawed back (an
     * {@code UPHOLD_COACH} dispute: the session is paid to the coach instead, which re-settles it through the
     * payout allocation). No-op when the booking's session was never refunded as money.
     *
     * @return the amount that had been recorded as refunded, or empty when there was no refund marker
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<BigDecimal> releaseSessionRefund(UUID packId, UUID bookingId) {
        if (packId == null) {
            return Optional.empty();
        }
        SessionPackPurchase pack = lockRetryer.withBoundedRetry("PackRefundAllocationService.release",
            () -> sessionPackPurchaseRepository.findByIdForUpdate(packId).orElse(null));
        Optional<BigDecimal> recorded = sessionPackPurchaseRepository.findRefundMarkerAmount(bookingId);
        if (pack == null || recorded.isEmpty()) {
            return Optional.empty();
        }
        sessionPackPurchaseRepository.deleteRefundMarker(bookingId);
        pack.releaseRefund(recorded.get());
        sessionPackPurchaseRepository.save(pack);
        log.info("[PACK_REFUND] booking={} pack={} refund of {} released (clawed back)", bookingId, packId,
            recorded.get());
        return recorded;
    }
}
