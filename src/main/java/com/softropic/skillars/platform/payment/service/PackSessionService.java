package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.booking.contract.BatchRuleViolationException;
import com.softropic.skillars.platform.booking.contract.BookingStatus;
import com.softropic.skillars.platform.booking.contract.ConflictingBookingItem;
import com.softropic.skillars.platform.booking.contract.PackPausedEvent;
import com.softropic.skillars.platform.booking.contract.PauseConflictResponse;
import com.softropic.skillars.platform.booking.contract.PausePackRequest;
import com.softropic.skillars.platform.booking.contract.SessionPackExhaustedEvent;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.contract.CreditLedgerEntryType;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.ParentCreditLedgerRepository;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchase;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.springframework.util.StringUtils.hasText;

@Service
@RequiredArgsConstructor
@Slf4j
public class PackSessionService {

    // skillars-deferred-164 AC4: derived from BookingStatus's own names rather than re-typed
    // literals, so a future rename of one of these constants fails to compile here instead of
    // silently drifting out of sync. Booking.status itself stays a plain String column (D164-F) —
    // this list is only the argument to BookingRepository.findConflictingBookingsForPause.
    private static final List<String> CONFLICT_STATUSES = List.of(
        BookingStatus.REQUESTED.name(), BookingStatus.ACCEPTED.name(),
        BookingStatus.CONFIRMED.name(), BookingStatus.UPCOMING.name());

    /**
     * fix/audit3-wallet-cashout-integrity: the restore reason of a coach cancellation with an EXCUSED reason
     * (weather, illness, family emergency, mutual agreement) — on an expired pack it is refunded spend-only, like
     * the parent's own cancel (owner decision).
     */
    public static final String RESTORE_REASON_COACH_EXCUSED_CANCELLATION = "COACH_EXCUSED_CANCELLATION";

    /**
     * fix/payment-pack-integrity: restore reasons that, had the pack already expired when the booking was
     * cancelled, would have been refunded to the wallet (the coach / admin cancellation and coach no-show
     * paths) — a restore that reaches an expired pack is refunded the same way.
     * fix/audit3-wallet-cashout-integrity: the parent-side reasons too ({@link #SPEND_ONLY_ON_EXPIRY_REASONS}) —
     * a session restored to a pack that expired between the cancel and the restore used to be lost.
     */
    private static final Set<String> WALLET_ON_EXPIRY_REASONS =
        Set.of("COACH_CANCELLATION", "COACH_NO_SHOW", "ADMIN_CANCELLATION",
            "PARENT_CANCELLATION", "PACK_PAUSE", RESTORE_REASON_COACH_EXCUSED_CANCELLATION);

    /**
     * fix/audit3-wallet-cashout-integrity: the parent-side reasons, whose expired-pack refund is SPEND-ONLY
     * credit (owner decision 2026-10-29: an expired-pack >24h parent cancel is never cashed out).
     */
    private static final Set<String> SPEND_ONLY_ON_EXPIRY_REASONS =
        Set.of("PARENT_CANCELLATION", "PACK_PAUSE", RESTORE_REASON_COACH_EXCUSED_CANCELLATION);

    private final SessionPackPurchaseRepository sessionPackPurchaseRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final BookingRepository bookingRepository;
    private final BookingService bookingService;
    private final ConfigService configService;
    private final CoachProfileRepository coachProfileRepository;
    private final UserRepository userRepository;
    private final PessimisticLockRetryer lockRetryer;
    private final PackRefundAllocationService packRefundAllocationService;
    private final CreditWalletService creditWalletService;
    private final ParentCreditLedgerRepository ledgerRepository;

    // skillars-deferred-136 AC3: field-injected (not constructor/final), lazy to break the
    // self-referential creation cycle — see pausePack's own Javadoc for why self-invocation is
    // required here (mirrors GdprErasureService's own identical field).
    @Autowired
    @Lazy
    private PackSessionService self;

    /** See {@link #pausePack}'s own Javadoc for why the D8 fix throws rather than returns. */
    private static final class PauseWindowConflictException extends RuntimeException {
        private final List<ConflictingBookingItem> conflicts;

        PauseWindowConflictException(List<ConflictingBookingItem> conflicts) {
            this.conflicts = conflicts;
        }
    }

    private static List<ConflictingBookingItem> toConflictingBookingItems(List<Booking> bookings, Instant now) {
        return bookings.stream()
            .map(b -> new ConflictingBookingItem(b.getId(), b.getRequestedStartTime(),
                b.getRequestedEndTime(), b.getStatus(), b.getCanonicalTimezone(), isPauseCancellable(b, now)))
            .toList();
    }

    /**
     * fix/payment-pack-integrity: a pause may cancel a booking only where a parent cancellation would return
     * the session anyway — a not-yet-paid booking (nothing to refund), or a paid one with more than 24h notice
     * before its original start. A paid booking inside the window is the parent's to cancel under the normal
     * rules (a forfeit); pausing must not turn it into a full refund.
     */
    private static boolean isPauseCancellable(Booking booking, Instant now) {
        boolean paid = BookingStatus.CONFIRMED.name().equals(booking.getStatus())
            || BookingStatus.UPCOMING.name().equals(booking.getStatus());
        return !paid || BookingService.hasCancellationNotice(
            booking.getRequestedStartTime(), booking.getOriginalStartTime(), now);
    }

    @Transactional
    public void deductSession(UUID purchaseId) {
        SessionPackPurchase purchase = lockRetryer.withBoundedRetry("PackSessionService.deductSession",
            () -> sessionPackPurchaseRepository.findByIdForUpdate(purchaseId)
                .orElseThrow(() -> new PaymentGatewayException("payment.packNotFound")));

        if (purchase.getRemainingSessions() <= 0) {
            throw new PaymentGatewayException("payment.packExhausted");
        }
        // fix/payment-session-pack-integrity: a pause extends expiresAt by its length, so spending a paused
        // pack would make the pause a free extension. The accept path records a payment failure for it,
        // as for an exhausted pack. fix/payment-pack-integrity: paused means inside [pausedFrom, pausedUntil).
        if (purchase.isPausedAt(Instant.now(ClockProvider.getClock()))) {
            throw new PaymentGatewayException("payment.packPaused");
        }
        // fix/payment-pack-integrity: a card dispute on the pack's charge is open — nothing is spent from the
        // pack until it is decided (won: unfrozen; lost: the remaining sessions are revoked).
        if (purchase.isDisputeFrozen()) {
            throw new PaymentGatewayException("payment.packFrozen");
        }
        // Expiry is deliberately NOT re-checked here: the policy is "book within the pack's validity; the
        // session may take place later" — BookingService.createBookingRequest checks it at request time.

        decrement(purchase);
    }

    /**
     * fix/payment-pack-integrity: an {@code UPHOLD_COACH} dispute resolution takes back a session that was
     * restored to the pack. That is a clawback, not a spend, so neither the pause nor the dispute freeze
     * applies — under {@link #deductSession}'s guards a paused pack made the resolution throw and roll back
     * for as long as the pause lasted. Joins the caller's transaction; {@code lockedPurchase} must have been
     * read under its row lock in that transaction.
     *
     * @return false when the pack has no session left to take back
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean deductSessionForClawback(SessionPackPurchase lockedPurchase) {
        SessionPackPurchase purchase = lockedPurchase;
        if (purchase.getRemainingSessions() <= 0) {
            return false;
        }
        decrement(purchase);
        return true;
    }

    private void decrement(SessionPackPurchase purchase) {
        UUID purchaseId = purchase.getPurchaseId();
        purchase.setRemainingSessions(purchase.getRemainingSessions() - 1);
        sessionPackPurchaseRepository.save(purchase);

        if (purchase.getRemainingSessions() == 0) {
            log.info("Session pack exhausted: purchaseId={} parentId={}", purchaseId, purchase.getParentId());
            // Reuse existing event from booking.contract — do not create a duplicate
            eventPublisher.publishEvent(new SessionPackExhaustedEvent(
                this, purchaseId, purchase.getParentId(), purchase.getCoachId()));
        }
    }

    /**
     * fix/payment-session-pack-integrity: gives back the pack session a cancelled booking had consumed —
     * exactly once per booking. Reached only from {@link PackSessionRestoreOutboxHandler} (the restore is
     * enqueued BEFORE_COMMIT with the cancellation, see {@link PackRestoreOutboxSupport}); the V195
     * {@code pack_session_restores} marker is claimed in this same transaction, so a re-driven outbox row
     * is a no-op. Lock first, then claim: the marker insert and the increment commit together or not at all.
     */
    @Transactional
    public void restoreSessionForBooking(UUID purchaseId, UUID bookingId, String reason) {
        SessionPackPurchase purchase = lockRetryer.withBoundedRetry("PackSessionService.restoreSession",
            () -> sessionPackPurchaseRepository.findByIdForUpdate(purchaseId).orElse(null));
        if (purchase == null) {
            // Throwing would only re-drive forever: the pack row is gone (FK cascade removes the marker too).
            log.error("[PACK_SESSION_RESTORE] pack {} for booking {} no longer exists — session NOT restored, "
                + "reconcile by hand", purchaseId, bookingId);
            return;
        }
        // fix/payment-pack-integrity: the restore runs off the outbox, after the cancellation committed — by then
        // the pack may have been revoked or may have expired, and a session given back to it would be worthless.
        if (purchase.isRevoked()) {
            if (SessionPackPurchase.REVOKED_COACH_SUSPENDED.equals(purchase.getRevokedReason())) {
                refundSessionToWallet(purchase, bookingId, "Coach unavailable — pack session refunded", false);
            } else {
                // CHARGEBACK_LOST / EXTERNAL_REFUND: the payer already has the pack's money back.
                log.warn("[PACK_SESSION_RESTORE] pack {} was revoked ({}) — session for booking {} NOT restored",
                    purchaseId, purchase.getRevokedReason(), bookingId);
            }
            return;
        }
        if (purchase.getExpiresAt().isBefore(Instant.now(ClockProvider.getClock())) && WALLET_ON_EXPIRY_REASONS.contains(reason)) {
            // The same outcome as the cancellation would have had, had the pack already expired at the time:
            // a coach / admin cancellation or a coach no-show on an expired pack is refunded to the wallet
            // (cashable); a parent-side one as spend-only credit.
            boolean spendOnly = SPEND_ONLY_ON_EXPIRY_REASONS.contains(reason);
            refundSessionToWallet(purchase, bookingId, spendOnly
                ? "Pack expired — session refunded as booking credit (" + reason + ")"
                : "Pack expired — session refunded (" + reason + ")", spendOnly);
            return;
        }
        if (sessionPackPurchaseRepository.insertRestoreMarker(bookingId, purchaseId, reason) == 0) {
            log.info("[PACK_SESSION_RESTORE] session for booking {} already restored to pack {} — re-drive is a no-op",
                bookingId, purchaseId);
            return;
        }
        purchase.setRemainingSessions(purchase.getRemainingSessions() + 1);
        sessionPackPurchaseRepository.save(purchase);
        log.info("Session restored to pack: purchaseId={} bookingId={} reason={}", purchaseId, bookingId, reason);
    }

    /**
     * Refunds a booking's pack session to the payer's wallet instead of restoring it — settled on the pack's
     * ledger ({@link PackRefundAllocationService}, idempotent per booking) and credited to the wallet in the same
     * transaction (this runs inside the restore outbox handler — the same {@code BOOKING_REFUND} row the refund
     * outbox would write, with its existence check). No restore marker is written, so an {@code UPHOLD_COACH}
     * clawback finds the wallet refund.
     */
    private void refundSessionToWallet(SessionPackPurchase purchase, UUID bookingId, String description,
                                       boolean spendOnly) {
        if (sessionPackPurchaseRepository.existsRestoreMarker(bookingId)) {
            log.info("[PACK_SESSION_RESTORE] session for booking {} was already restored to pack {} — no refund",
                bookingId, purchase.getPurchaseId());
            return;
        }
        BigDecimal amount = packRefundAllocationService.allocateSessionRefund(
            purchase.getPurchaseId(), bookingId, purchase.perSessionShare());
        if (amount == null || amount.signum() <= 0) {
            log.warn("[PACK_SESSION_RESTORE] booking={} pack={} — the session settles at 0.00, no wallet credit",
                bookingId, purchase.getPurchaseId());
            return;
        }
        if (ledgerRepository.existsByReferenceIdAndType(bookingId, CreditLedgerEntryType.BOOKING_REFUND)) {
            log.info("[PACK_SESSION_RESTORE] booking {} already has a BOOKING_REFUND — no second credit", bookingId);
            return;
        }
        // CASHABLE (spend-only part 0) for coach-side / suspension reasons — paid money the payer is owed back.
        // fix/audit3-wallet-cashout-integrity: SPEND-ONLY for a parent-side reason (a parent cancel / pack pause /
        // excused coach cancel whose pack expired before the restore ran) — the same as C6's expired-pack path.
        creditWalletService.writeLedgerEntry(purchase.getParentId(), amount, CreditLedgerEntryType.BOOKING_REFUND,
            bookingId, description, null, null, spendOnly ? amount : BigDecimal.ZERO);
        log.info("[PACK_SESSION_RESTORE] booking={} pack={} — session refunded to the wallet ({}) instead of restored",
            bookingId, purchase.getPurchaseId(), amount);
    }

    /**
     * fix/payment-pack-integrity: a card dispute opened on the charge that bought a pack — the pack is frozen
     * (no booking request, no deduction) until the dispute is decided. Joins the webhook transaction.
     *
     * @return true when a pack was frozen by this call
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean freezeForDispute(String paymentIntentId) {
        SessionPackPurchase pack = lockPackByPaymentIntent(paymentIntentId);
        if (pack == null || pack.isRevoked() || pack.isDisputeFrozen()) {
            return false;
        }
        pack.setDisputeFrozenAt(Instant.now(ClockProvider.getClock()));
        sessionPackPurchaseRepository.save(pack);
        log.warn("[PACK_DISPUTE_FREEZE] pack {} frozen — card dispute open on {}", pack.getPurchaseId(), paymentIntentId);
        return true;
    }

    /** fix/payment-pack-integrity: the dispute on the pack's charge was won — the pack can be used again. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean liftDisputeFreeze(String paymentIntentId) {
        SessionPackPurchase pack = lockPackByPaymentIntent(paymentIntentId);
        if (pack == null || !pack.isDisputeFrozen()) {
            return false;
        }
        pack.setDisputeFrozenAt(null);
        sessionPackPurchaseRepository.save(pack);
        log.info("[PACK_DISPUTE_FREEZE] pack {} unfrozen — dispute on {} won", pack.getPurchaseId(), paymentIntentId);
        return true;
    }

    /**
     * fix/payment-pack-integrity: the payer got the pack's money back outside the app (a lost card dispute, or
     * a full refund from the Stripe Dashboard) — its remaining sessions are revoked. Sessions already played
     * stay settled as they are. Joins the webhook transaction.
     *
     * @return the number of sessions revoked, or -1 when no (unrevoked) pack was bought with this charge
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int revokeForCharge(String paymentIntentId, String reason) {
        SessionPackPurchase pack = lockPackByPaymentIntent(paymentIntentId);
        if (pack == null || pack.isRevoked()) {
            return -1;
        }
        int revoked = pack.getRemainingSessions();
        revoke(pack, reason);
        log.error("[PACK_REVOKED] pack {} — {} remaining session(s) revoked ({}) on {}", pack.getPurchaseId(), revoked,
            reason, paymentIntentId);
        return revoked;
    }

    /**
     * fix/payment-pack-integrity: a suspended coach can no longer be booked, so the unused sessions of every
     * unexpired pack sold for them are refunded to the payer's wallet at the pack's locked price (cashable:
     * it is paid money), settled on the pack's ledger, and the pack is revoked. Sessions of bookings the
     * suspension cancelled are refunded per booking ({@link #restoreSessionForBooking} routes them to the
     * wallet once the pack is revoked). A pack under an open card dispute is left alone (the dispute decides
     * it); an expired pack's unused sessions were already forfeited. Idempotent: a revoked pack is skipped.
     * No-op unless the coach is still suspended when this runs (they may have been reinstated meanwhile).
     *
     * @return the number of packs refunded
     */
    @Transactional
    public int refundUnusedSessionsForSuspendedCoach(UUID coachId) {
        CoachProfile coach = coachProfileRepository.findById(coachId).orElse(null);
        if (coach == null || coach.getStatus() != CoachProfileStatus.SUSPENDED) {
            log.info("[PACK_SUSPENSION_REFUND] coach {} is not suspended (any more) — nothing refunded", coachId);
            return 0;
        }
        Instant now = Instant.now(ClockProvider.getClock());
        int refunded = 0;
        for (UUID purchaseId : sessionPackPurchaseRepository.findUnrevokedPackIdsWithSessionsByCoachId(coachId)) {
            SessionPackPurchase pack = lockRetryer.withBoundedRetry("PackSessionService.refundUnusedSessions",
                () -> sessionPackPurchaseRepository.findByIdForUpdate(purchaseId).orElse(null));
            if (pack == null || pack.isRevoked() || pack.getRemainingSessions() <= 0) {
                continue;
            }
            if (pack.isDisputeFrozen()) {
                log.warn("[PACK_SUSPENSION_REFUND] pack {} is under an open card dispute — not refunded", purchaseId);
                continue;
            }
            if (pack.getExpiresAt().isBefore(now)) {
                continue;
            }
            int sessions = pack.getRemainingSessions();
            BigDecimal total = BigDecimal.ZERO;
            for (int i = 0; i < sessions; i++) {
                BigDecimal value = pack.nextSettlementValue();
                BigDecimal amount = value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
                pack.recordRefund(amount);
                total = total.add(amount);
            }
            revoke(pack, SessionPackPurchase.REVOKED_COACH_SUSPENDED);
            if (total.signum() > 0) {
                // reference = the pack id (one BOOKING_REFUND per reference, so a re-run cannot credit twice);
                // traced to the pack's charge, so a later card dispute on it takes the unspent credit back.
                // Written in this transaction (atomic with the revocation), not via the refund outbox: this runs
                // as an outbox handler, and a handler must not depend on OutboxService (bean cycle).
                // CASHABLE (spend-only part 0): it is paid money for sessions the coach can no longer deliver.
                creditWalletService.writeLedgerEntry(pack.getParentId(), total, CreditLedgerEntryType.BOOKING_REFUND,
                    purchaseId, "Coach unavailable — " + sessions + " unused pack session(s) refunded",
                    pack.getStripePaymentIntentId(), pack.getStripePaymentIntentId() != null ? total : null,
                    BigDecimal.ZERO);
            }
            log.warn("[PACK_SUSPENSION_REFUND] coach={} pack={} — {} unused session(s) refunded to parent {} ({})",
                coachId, purchaseId, sessions, pack.getParentId(), total);
            refunded++;
        }
        return refunded;
    }

    private void revoke(SessionPackPurchase pack, String reason) {
        pack.setRemainingSessions(0);
        pack.setRevokedAt(Instant.now(ClockProvider.getClock()));
        pack.setRevokedReason(reason);
        pack.setDisputeFrozenAt(null);
        sessionPackPurchaseRepository.save(pack);
    }

    private SessionPackPurchase lockPackByPaymentIntent(String paymentIntentId) {
        if (paymentIntentId == null || paymentIntentId.isBlank()) {
            return null;
        }
        UUID purchaseId = sessionPackPurchaseRepository.findByStripePaymentIntentId(paymentIntentId)
            .map(SessionPackPurchase::getPurchaseId).orElse(null);
        if (purchaseId == null) {
            return null;
        }
        return lockRetryer.withBoundedRetry("PackSessionService.lockPackByPaymentIntent",
            () -> sessionPackPurchaseRepository.findByIdForUpdate(purchaseId).orElse(null));
    }

    @Transactional(readOnly = true)
    public boolean hasActivePack(Long playerId, UUID coachId) {
        return !sessionPackPurchaseRepository.findActivePacks(playerId, coachId, Instant.now()).isEmpty();
    }

    // Story Deferred-75 AC6: batch equivalent of hasActivePack, replacing HomeworkAssignmentService
    // .getLockerRoomDrills's per-coach N+1 loop with one query.
    @Transactional(readOnly = true)
    public Set<UUID> hasActivePackForAnyOf(Long playerId, Set<UUID> coachIds) {
        if (coachIds.isEmpty()) return Set.of();
        if (coachIds.size() > 1000) {
            throw new IllegalArgumentException(
                "coachIds Set size " + coachIds.size() + " exceeds maximum of 1000. " +
                "Consider implementing chunked batch queries for large coach lists.");
        }
        return sessionPackPurchaseRepository.findCoachIdsWithActivePack(playerId, coachIds, Instant.now());
    }

    @Transactional(readOnly = true)
    public UUID getActivePackId(Long playerId, UUID coachId) {
        List<SessionPackPurchase> packs = sessionPackPurchaseRepository.findActivePacks(playerId, coachId, Instant.now());
        if (!packs.isEmpty()) return packs.get(0).getPurchaseId();
        return sessionPackPurchaseRepository.findTopByPlayerIdAndCoachIdOrderByCreatedAtDesc(playerId, coachId)
            .map(SessionPackPurchase::getPurchaseId)
            .orElse(null);
    }

    /**
     * Single-snapshot resolution of the active pack to attach to a new booking. Unlike the
     * hasActivePack()/getActivePackId() pair, this is one query — no window between an
     * existence check and a separate resolve where the pack could be consumed/expire, and no
     * unfiltered "most recent pack ever" fallback that could attach an exhausted/expired pack.
     */
    @Transactional(readOnly = true)
    public UUID findActivePackId(Long playerId, UUID coachId) {
        List<SessionPackPurchase> packs = sessionPackPurchaseRepository.findActivePacks(playerId, coachId, Instant.now());
        if (packs.isEmpty()) {
            throw new OperationNotAllowedException(
                "No effective session credits available for this coach", SecurityError.MISSING_RIGHTS);
        }
        return packs.get(0).getPurchaseId();
    }

    /**
     * skillars-deferred-136 AC3: thin, non-transactional wrapper around {@link
     * #pausePackTransactional} — required so the D8 fix below can roll back this transaction's own
     * already-committed-within-it {@code cancelDueToPause} calls when a late conflict is found, while
     * still returning the SAME {@code PauseConflictResponse(false, items, null)} shape the client
     * already expects from the Step-1/D1 conflict paths (a normal return from an {@code @Transactional}
     * method commits everything written so far in it — only an exception crossing the proxy boundary
     * triggers rollback, and this method IS that boundary). Mirrors this codebase's own established
     * self-invocation split pattern (e.g. {@code GdprErasureService.erase}/{@code eraseTransactional}).
     */
    public PauseConflictResponse pausePack(Long parentId, UUID purchaseId, PausePackRequest req) {
        try {
            return self.pausePackTransactional(parentId, purchaseId, req);
        } catch (PauseWindowConflictException e) {
            return new PauseConflictResponse(false, e.conflicts, null);
        }
    }

    /**
     * skillars-deferred-136 AC3 (D5) — disclosed, not silent: the {@code session_pack_purchases} lock
     * below is deliberately held for the WHOLE method, matching the pre-existing shape, not narrowed
     * to just before the final write. Narrowing was investigated (this AC's own critical caveat) and
     * found safe against the specific lock-ordering hazard originally raised — {@code
     * BookingService.transition()} (reached via {@code cancelDueToPause} in the loop below) was read in
     * full and acquires exactly one lock, on the {@code booking} table's own single row, no others —
     * but narrowing would ALSO require moving the {@code purchase.getPausedUntil() != null} "one pause
     * per lifetime" check (currently read once, under this same lock, right after acquisition) to
     * immediately before the final write too, so a second concurrent {@code pausePack} racing on the
     * SAME purchase can't both pass that check under an unlocked read and only discover the conflict
     * after already cancelling bookings. That is a second, independent correctness surface beyond the
     * D8 booking-conflict recheck this AC already adds, and disproportionate to what D5 itself
     * (a contention/timing concern, not a functional bug) asked for. Left as-is.
     */
    @Transactional
    public PauseConflictResponse pausePackTransactional(Long parentId, UUID purchaseId, PausePackRequest req) {
        SessionPackPurchase purchase = lockRetryer.withBoundedRetry("PackSessionService.pausePack",
            () -> sessionPackPurchaseRepository.findByIdForUpdate(purchaseId)
                .orElseThrow(() -> new PaymentGatewayException("payment.packNotFound")));
        if (!Objects.equals(purchase.getParentId(), parentId)) {
            throw new OperationNotAllowedException("Parent does not own this session pack", SecurityError.MISSING_RIGHTS);
        }
        // fix/pack-status-badges: a revoked pack (its sessions were taken away) or one frozen by an open card
        // dispute cannot be paused — a pause would cancel bookings and push out the expiry of a pack whose fate
        // the dispute decides. Checked before the "not active" test so the client gets the specific reason.
        if (purchase.isRevoked()) {
            throw new PaymentGatewayException("payment.packRevoked");
        }
        if (purchase.isDisputeFrozen()) {
            throw new PaymentGatewayException("payment.packFrozen");
        }
        Instant now = Instant.now(ClockProvider.getClock());
        if (purchase.getRemainingSessions() <= 0 || purchase.getExpiresAt().isBefore(now)) {
            throw new OperationNotAllowedException("Pack is not active", SecurityError.MISSING_RIGHTS);
        }
        // AC 4: one pause per pack lifetime
        if (purchase.getPausedUntil() != null) {
            throw new BatchRuleViolationException("booking.packAlreadyPaused");
        }

        // skillars-deferred-103 code-review P11: a missing coach here is a data-integrity failure,
        // not an authorization problem — session_pack_purchases.coach_id carries an FK
        // (fk_spp_coach), so the parent has rights and the referenced row has vanished. Throw
        // IllegalStateException so ApiAdvice maps it to 500 + ERROR alerting (see
        // ApiAdvice.illegalStateExceptionHandler), instead of a misleading 403 MISSING_RIGHTS.
        UUID coachId = purchase.getCoachId();
        CoachProfile coach = coachProfileRepository.findById(coachId)
            .orElseThrow(() -> new IllegalStateException(
                "Coach profile " + coachId + " referenced by session pack " + purchaseId
                    + " does not exist — data-integrity failure (FK fk_spp_coach)"));

        // skillars-deferred-103 AC6: defensive default for max pause days config.
        // skillars-deferred-107 AC1: also range-guard it — a stored 0/negative/absurd value would
        // otherwise be accepted verbatim and reject every pauseDurationDays >= 1 as
        // booking.pauseDurationInvalid with nothing pointing at the config. Falls back to the registry default (90) + WARN;
        // ConfigStartupAssertion additionally refuses to boot on a bad stored value (failFast key).
        long maxDays = configService.getBoundedLong(ConfigBounds.PACK_PAUSE_MAX_DAYS);
        if (req.pauseDurationDays() < 1 || req.pauseDurationDays() > maxDays) {
            throw new BatchRuleViolationException("booking.pauseDurationInvalid");
        }

        // fix/payment-pack-integrity: the pause must start in the future — strictly after now, not merely
        // "today" in the coach's zone (skillars-deferred-103 AC5's date check let a pause start hours in the
        // past, so it could reach bookings that had already started).
        Instant pauseStart = req.pauseStartDate();
        if (!pauseStart.isAfter(now)) {
            throw new BatchRuleViolationException("booking.pauseStartInPast");
        }
        if (!pauseStart.isBefore(purchase.getExpiresAt())) {
            throw new BatchRuleViolationException("booking.pauseStartAfterExpiry");
        }
        Instant pauseEnd = pauseStart.plus(Duration.ofDays(req.pauseDurationDays()));

        Long playerId = purchase.getPlayerId();
        // fix/payment-pack-integrity: only bookings drawn on THIS pack conflict with its pause.
        List<Booking> conflicting = bookingRepository.findConflictingBookingsForPause(
            purchaseId, playerId, coachId, pauseStart, pauseEnd, CONFLICT_STATUSES);

        // fix/payment-pack-integrity: a paid booking inside the 24h cancellation notice window cannot be
        // cancelled by a pause — that turned a forfeit into a full refund. The pause is refused (whatever the
        // client confirmed) with the conflict list, the blocking bookings marked not cancellable; the parent
        // can start the pause after that booking, or cancel it under the normal rules.
        if (conflicting.stream().anyMatch(b -> !isPauseCancellable(b, now))) {
            return new PauseConflictResponse(false, toConflictingBookingItems(conflicting, now), null);
        }

        List<UUID> confirmedIds = req.confirmedCancellationIds() != null
            ? req.confirmedCancellationIds() : List.of();

        // Step 1: conflicts exist and not yet confirmed → return list without applying
        if (!conflicting.isEmpty() && confirmedIds.isEmpty()) {
            return new PauseConflictResponse(false, toConflictingBookingItems(conflicting, now), null);
        }

        // Validate confirmedIds against live conflict set; collect times without N+1 queries
        Map<UUID, Instant> conflictMap = conflicting.stream()
            .collect(Collectors.toMap(Booking::getId, Booking::getRequestedStartTime));
        List<UUID> validatedIds = confirmedIds.stream()
            .distinct()
            .filter(conflictMap::containsKey)
            .toList();

        // skillars-deferred-136 AC3 (D1): validatedIds is confirmedIds FILTERED down to real
        // conflicts — it never checked the reverse, that EVERY real conflict was covered by
        // confirmedIds. A client that confirms only a subset (or a stale/racing request that omits
        // the field) would otherwise have that subset cancelled while the pause still applied
        // unconditionally, leaving the un-confirmed conflicting bookings sitting inside a now-paused
        // window with no further check. Same response shape as the original unconfirmed case (Step 1
        // above) — the client re-confirms against the same conflict list either way.
        if (!validatedIds.containsAll(conflictMap.keySet())) {
            return new PauseConflictResponse(false, toConflictingBookingItems(conflicting, now), null);
        }

        List<Instant> cancelledTimes = validatedIds.stream()
            .map(conflictMap::get)
            .toList();
        for (UUID bookingId : validatedIds) {
            bookingService.cancelDueToPause(bookingId, purchaseId, coachId, parentId);
        }

        // skillars-deferred-136 AC3 (D8): a booking created between the :195-196 conflict read (now
        // above) and this point — e.g. a brand-new createBookingRequest for the same coach/player/
        // window, which takes no session_pack_purchases lock — would never have been in confirmedIds
        // and would otherwise sit uncancelled inside the pause with no signal to the client. Re-run
        // the identical query under the SAME still-held lock immediately before the write below; any
        // id not already accounted for in the original conflict set must ABORT rather than return
        // normally — the cancelDueToPause calls just above already wrote to THIS transaction, and only
        // an exception crossing pausePackTransactional's own proxy boundary rolls them back (see
        // pausePack's own Javadoc). The caught exception is converted back to the identical
        // PauseConflictResponse(false, items, null) shape the client already expects.
        List<Booking> recheckConflicting = bookingRepository.findConflictingBookingsForPause(
            purchaseId, playerId, coachId, pauseStart, pauseEnd, CONFLICT_STATUSES);
        boolean hasNewConflict = recheckConflicting.stream()
            .anyMatch(b -> !conflictMap.containsKey(b.getId()));
        if (hasNewConflict) {
            throw new PauseWindowConflictException(toConflictingBookingItems(recheckConflicting, now));
        }

        // Apply pause — fix/payment-pack-integrity: its start is stored, so the pack is paused over
        // [pauseStart, pauseEnd) only, not from the moment the pause was scheduled.
        purchase.setPausedFrom(pauseStart);
        purchase.setPausedUntil(pauseEnd);
        purchase.setExpiresAt(purchase.getExpiresAt().plus(Duration.ofDays(req.pauseDurationDays())));
        sessionPackPurchaseRepository.save(purchase);

        // skillars-deferred-103 AC7: publish confirmation event only if parent email is available.
        // Coach is already loaded (AC5) and guaranteed non-null, so use it directly.
        String parentEmail = userRepository.findById(parentId)
            .map(u -> u.getEmail())
            .filter(email -> hasText(email))
            .orElse(null);
        if (parentEmail == null) {
            log.error("Pack pause notification skipped — parent email missing/blank: "
                + "parentId={} purchaseId={} coachId={}",
                parentId, purchaseId, coachId);
        } else {
            eventPublisher.publishEvent(new PackPausedEvent(
                this, purchase.getPurchaseId(), parentId, parentEmail, coach.getDisplayName(),
                purchase.getExpiresAt(), cancelledTimes, coach.getCanonicalTimezone()
            ));
        }

        return new PauseConflictResponse(true, List.of(), purchase.getExpiresAt());
    }
}
