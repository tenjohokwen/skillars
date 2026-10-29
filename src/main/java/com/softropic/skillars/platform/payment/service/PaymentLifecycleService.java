package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.BatchBookingAcceptedEvent;
import com.softropic.skillars.platform.booking.contract.BookingAcceptedEvent;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.payment.contract.BookingPaymentStatus;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.contract.exception.ChargeOutcomeUnknownException;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentLifecycleService {

    private static final String SETTLE_ABORTED_COUNTER = "booking.payment.settle_aborted";

    private final CreditWalletService creditWalletService;
    private final PackSessionService packSessionService;
    private final PaymentGateway paymentGateway;
    private final BookingPaymentRepository bookingPaymentRepository;
    private final BookingRepository bookingRepository;
    private final BookingPaymentPersistenceService persistenceService;
    private final PlatformTransactionManager transactionManager;
    private final MeterRegistry meterRegistry;

    /**
     * Settles one batch booking per transaction. Without this, every booking in a batch shares the
     * listener's transaction: one failure marks it rollback-only and the successfully-settled
     * siblings are silently discarded at commit, stranded in PAYMENT_PENDING while the swallowed
     * UnexpectedRollbackException leaves no trace (reproduced during the Deferred-12 code review).
     */
    private TransactionTemplate perBookingTx;

    @PostConstruct
    void initPerBookingTx() {
        perBookingTx = new TransactionTemplate(transactionManager);
        perBookingTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ─── Reservation-aware guards (UAT.3 AC3) ─────────────────────────────────

    /**
     * A row no longer means "settled": AC1 writes one BEFORE the charge. Only a terminal status
     * proves this booking is done; CAPTURE_PENDING means a prior attempt died mid-capture and must
     * escalate rather than be silently skipped as a duplicate delivery.
     */
    private boolean isSettled(UUID bookingId) {
        return bookingPaymentRepository.findById(bookingId)
            .map(bp -> BookingPaymentStatus.isTerminal(bp.getStatus()))
            .orElse(false);
    }

    private boolean hasReservation(UUID bookingId) {
        return bookingPaymentRepository.findById(bookingId)
            .map(bp -> BookingPaymentStatus.CAPTURE_PENDING.equals(bp.getStatus()))
            .orElse(false);
    }

    /**
     * Reserves, converting a thrown reservation into a reported {@code null} rather than letting it
     * escape. Both callers are AFTER_COMMIT listeners, where an escaping exception produces no
     * application-level signal at all — the very silence AC4 exists to remove, and it would be
     * perverse to close it around {@code transition(...)} while leaving it open around the
     * reservation guarding the Stripe call. {@code reserveCapture} can genuinely throw: it takes a
     * NOWAIT PESSIMISTIC_WRITE lock, bounded-retried by {@code PessimisticLockRetryer} (worst case
     * ~2-3s), that surfaces as {@code PessimisticLockingFailureException} once exhausted, and
     * inserts against a primary key.
     *
     * <p>Returning null rather than rethrowing is deliberate. Nothing has been charged — the
     * reservation is what gates the gateway call — so the booking simply rests in PAYMENT_PENDING
     * with no payment row, which is precisely the state PaymentPendingSweeper can decide safely.
     */
    private CaptureReservation reserveOrReport(UUID bookingId, BigDecimal intendedCredit,
                                               BigDecimal intendedStripe, UUID batchId, String chargeKey) {
        try {
            return persistenceService.reserveCapture(bookingId, intendedCredit, intendedStripe, batchId, chargeKey);
        } catch (RuntimeException e) {
            Counter.builder(SETTLE_ABORTED_COUNTER)
                .tag("reason", "reservation_failed")
                .register(meterRegistry)
                .increment();
            log.error("Capture reservation failed, so no charge was attempted: bookingId={} batchId={}. "
                    + "The booking stays in PAYMENT_PENDING with no payment row and is recoverable "
                    + "by PaymentPendingSweeper.", bookingId, batchId, e);
            return null;
        }
    }

    /**
     * Reports a settle that must not proceed. CAPTURE_UNCONFIRMED is ERROR because money may
     * already be at Stripe with nothing recording it — an operator has to reconcile that booking
     * by hand, following the runbook. The other two are expected and correct.
     *
     * <p><strong>Never re-charge on CAPTURE_UNCONFIRMED.</strong> A duplicate delivery landing on a
     * reserved row means the prior attempt's outcome is unknown; charging again risks
     * double-charging the parent. The booking stays in PAYMENT_PENDING and PaymentPendingSweeper
     * escalates it.
     */
    private void abortSettle(UUID bookingId, CaptureReservation reservation) {
        Counter.builder(SETTLE_ABORTED_COUNTER)
            .tag("reason", reservation.name().toLowerCase(Locale.ROOT))
            .register(meterRegistry)
            .increment();
        if (reservation == CaptureReservation.CAPTURE_UNCONFIRMED) {
            log.error("Settle aborted on an outstanding capture reservation: bookingId={} — a prior "
                    + "attempt reserved and did not finish, so money may already be at Stripe. "
                    + "Reconcile this booking by hand (see runbook: CAPTURE_PENDING).", bookingId);
            return;
        }
        log.warn("Settle aborted: bookingId={} reason={}", bookingId, reservation);
    }

    /**
     * feat/coach-payouts-pack-and-credit (C3 item 3, the #318 follow-up): the wallet balance a settlement
     * splits on. When the payer has credit, it is re-read under the per-payer wallet lock
     * ({@link CreditWalletService#lockAndGetBalance}, the {@code stripe_customers} row lock a cash-out
     * takes) and that lock is held until this listener's transaction commits — i.e. through the
     * {@code BOOKING_DEDUCTION} write — so a concurrent cash-out (or a second settlement for the same
     * payer) can no longer spend the same credit and overdraw the wallet.
     *
     * <p>Disclosed trade-off: on a mixed credit + card settlement the lock is still held while Stripe is
     * called. A cash-out for the same payer during those seconds waits through its NOWAIT retry budget
     * (~3s) and may get a retryable 409, and a second settlement for the same payer simply waits (its
     * lock is blocking, so an accepted booking is never stranded by contention); the alternative — debiting the credit in its own committed
     * transaction before the charge — would strand debited credit on a crash between the two. With no
     * credit (the common case) no lock is taken at all: there is nothing to overdraw.
     * fix/payment-charge-idempotency (L2): the hold is now bounded by the Stripe SDK timeouts configured in
     * {@code PaymentConfig} (connect / read / network retries) instead of the SDK's 80s-read defaults.
     */
    private BigDecimal walletBalanceForSettlement(Long parentId) {
        BigDecimal unlocked = creditWalletService.getBalance(parentId);
        if (unlocked.signum() <= 0) {
            return unlocked;
        }
        return creditWalletService.lockAndGetBalance(parentId);
    }

    // ─── Single booking ───────────────────────────────────────────────────────

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingAccepted(BookingAcceptedEvent event) {
        if (isSettled(event.getBookingId())) {
            log.warn("Duplicate BookingAcceptedEvent ignored: bookingId={}", event.getBookingId());
            return;
        }
        // A CAPTURE_PENDING row is NOT a duplicate delivery — it is a previous attempt that died
        // mid-capture. Escalating beats silently skipping it, and re-charging is never an option.
        if (hasReservation(event.getBookingId())) {
            abortSettle(event.getBookingId(), CaptureReservation.CAPTURE_UNCONFIRMED);
            return;
        }

        if (event.getSessionPackPurchaseId() != null) {
            handlePackBasedBooking(event.getBookingId(), event.getSessionPackPurchaseId(),
                event.getParentId(), event.getParentEmail(), event.getCoachDisplayName(),
                event.getRequestedStartTime(), event.getCanonicalTimezone());
            return;
        }

        handleCreditBasedBooking(event);
    }

    private void handlePackBasedBooking(UUID bookingId, UUID purchaseId, Long parentId,
                                        String parentEmail, String coachDisplayName,
                                        Instant requestedStartTime, String canonicalTimezone) {
        try {
            packSessionService.deductSession(purchaseId);
        } catch (PaymentGatewayException | DataAccessException e) {
            // skillars-deferred-103 AC3 (+ code-review resolution 2026-09-09): record a payment
            // failure for the outcomes the parent must be told about —
            //   * PaymentGatewayException → expected business failure (pack not found / exhausted);
            //   * DataAccessException → any persistence-layer fault in deductSession: a transient
            //     one (the row-lock retry budget in PessimisticLockRetryer exhausted under
            //     contention) or a non-transient one (constraint violation / DataIntegrityViolation).
            //     This listener is AFTER_COMMIT, so an escaping exception is only logged and never
            //     retried; recording an unresolved payment (+ notify) beats silently stranding the
            //     booking, and skillars-deferred-58 AC1's REQUIRES_NEW persistPaymentFailure is
            //     built to survive the caller's rollback-only transaction here.
            // Any other RuntimeException (NPE, IllegalStateException, IllegalArgumentException)
            // indicates a programming defect and is deliberately left to propagate — it must not be
            // disguised as an expected business failure.
            log.error("Pack session deduction failed: bookingId={} purchaseId={} error={}",
                bookingId, purchaseId, e.getMessage(), e);
            try {
                persistenceService.persistPaymentFailure(bookingId, BigDecimal.ZERO,
                    parentId, parentEmail, coachDisplayName, requestedStartTime, canonicalTimezone);
            } catch (RuntimeException pfe) {
                log.error("Failed to persist pack payment failure record: bookingId={} purchaseId={} error={}",
                    bookingId, purchaseId, pfe.getMessage(), pfe);
            }
            return;
        }
        persistenceService.persistPaymentSuccess(bookingId, BigDecimal.ZERO, BigDecimal.ZERO, null, null,
            parentId, parentEmail, coachDisplayName, requestedStartTime, canonicalTimezone);
    }

    private void handleCreditBasedBooking(BookingAcceptedEvent event) {
        BigDecimal balance = walletBalanceForSettlement(event.getParentId());
        // fix/payment-agreed-price-lock: the same split rule as the batch path, which clamps a negative
        // balance to zero (balance.min(price) here used to charge the card price + |balance|).
        // fix/payment-charge-idempotency (L3): a card part below Stripe's minimum is re-balanced, not declined.
        CreditCardSplit split = CreditCardSplit.of(balance, event.getSessionPrice(), paymentGateway.minimumCardCharge());
        BigDecimal creditToUse = split.creditApplied();
        BigDecimal stripeAmount = split.cardCharge().signum() == 0 ? BigDecimal.ZERO : split.cardCharge();

        if (stripeAmount.compareTo(BigDecimal.ZERO) > 0) {
            // UAT.3 AC3. Reserve only when a Stripe call is actually about to happen: a fully
            // credit-covered booking touches Stripe never, and a row from it would break the
            // invariant "no row ⇒ no charge was attempted" that the sweeper reads.
            //
            // DEADLOCK CONSTRAINT: reserveCapture is REQUIRES_NEW and takes a PESSIMISTIC_WRITE
            // lock on booking.bookings on a SECOND connection. This transaction must therefore
            // hold no lock and no uncommitted write on that booking row here — today it only
            // issues reads, plus (when the payer has credit) the stripe_customers wallet lock, which
            // reserveCapture never touches. If a future edit adds a write above this line, it
            // deadlocks with NO timeout: a plain UPDATE ignores the jakarta.persistence.lock.timeout=0
            // (NOWAIT) hint, which applies only to the locked SELECT.
            //
            // fix/payment-charge-idempotency: the charge's idempotency key is written onto the
            // reservation in that same committed transaction, BEFORE Stripe is called.
            String chargeKey = ChargeKeys.forBooking(event.getBookingId());
            CaptureReservation reservation = reserveOrReport(
                event.getBookingId(), creditToUse, stripeAmount, null, chargeKey);
            if (reservation != CaptureReservation.RESERVED) {
                if (reservation != null) {
                    abortSettle(event.getBookingId(), reservation);
                }
                return;
            }
            String paymentIntentId;
            try {
                paymentIntentId = paymentGateway.chargeAndCapture(
                    event.getBookingId(), event.getParentId(), event.getCoachId(), stripeAmount, chargeKey);
            } catch (ChargeOutcomeUnknownException e) {
                // The card may have been charged: never decline here. The reservation keeps the key and
                // ChargeOutcomeReconciler settles or declines once Stripe can say which.
                reportOutcomeUnknown(List.of(event.getBookingId()), null, chargeKey);
                return;
            } catch (PaymentGatewayException e) {
                log.error("Stripe charge failed for booking {}: {}", event.getBookingId(), e.getMessage());
                persistenceService.persistPaymentFailure(event.getBookingId(), BigDecimal.ZERO,
                    event.getParentId(), event.getParentEmail(), event.getCoachDisplayName(),
                    event.getRequestedStartTime(), event.getCanonicalTimezone());
                return;
            }
            recordChargeIntentQuietly(List.of(event.getBookingId()), paymentIntentId);
            settleChargedBookingOrCompensate(event, creditToUse, stripeAmount, paymentIntentId);
            return;
        }

        // Credit only — nothing reached Stripe. BOOKING_DEDUCTION + BookingPayment + status transition
        // all commit atomically, and a failure rolls all of them back with nothing to compensate.
        persistenceService.persistPaymentSuccess(event.getBookingId(), creditToUse, stripeAmount,
            null, null, event.getParentId(), event.getParentEmail(), event.getCoachDisplayName(),
            event.getRequestedStartTime(), event.getCanonicalTimezone());
    }

    /**
     * fix/payment-charge-idempotency (M1): the single-booking twin of the batch path's post-charge
     * compensation (#335). The settle (BOOKING_DEDUCTION + CAPTURED row + transition) runs in its own
     * transaction so a failure there — the booking row locked by another transaction past the NOWAIT retry
     * budget, a DB blip, an optimistic-lock clash — no longer rolls back silently while the parent's card
     * stays charged: the booking is declined and the card share credited back to the wallet (the credit
     * share was never debited — it commits only with the confirm). If the compensation fails too, the
     * reservation survives as CAPTURE_PENDING with its charge key and PaymentIntent id, and
     * ChargeOutcomeReconciler retries the settle.
     */
    private void settleChargedBookingOrCompensate(BookingAcceptedEvent event, BigDecimal creditToUse,
                                                  BigDecimal stripeAmount, String paymentIntentId) {
        try {
            perBookingTx.executeWithoutResult(s -> persistenceService.persistPaymentSuccess(event.getBookingId(),
                creditToUse, stripeAmount, paymentIntentId, null, event.getParentId(), event.getParentEmail(),
                event.getCoachDisplayName(), event.getRequestedStartTime(), event.getCanonicalTimezone()));
        } catch (RuntimeException e) {
            log.error("[SETTLE_FAILED_AFTER_CHARGE] bookingId={} intentId={} card={} — declining and returning "
                + "the card share as wallet credit", event.getBookingId(), paymentIntentId, stripeAmount, e);
            try {
                persistenceService.declineBookingAfterCharge(event.getBookingId(), null, BigDecimal.ZERO,
                    stripeAmount, paymentIntentId, event.getParentId(),
                    "Booking could not be confirmed - payment returned as credit");
            } catch (RuntimeException de) {
                log.error("[SINGLE_DECLINE_REFUND_FAILED] bookingId={} parentId={} intentId={} card={} — decline and "
                        + "refund both rolled back; the CAPTURE_PENDING reservation is left for ChargeOutcomeReconciler",
                    event.getBookingId(), event.getParentId(), paymentIntentId, stripeAmount, de);
            }
        }
    }

    private void recordChargeIntentQuietly(List<UUID> bookingIds, String paymentIntentId) {
        try {
            persistenceService.recordChargeIntent(bookingIds, paymentIntentId);
        } catch (RuntimeException e) {
            // Bookkeeping only: the reservation's charge key still finds the PaymentIntent.
            log.error("[STRIPE_INTENT_RECORD_FAILED] charge captured, intent id not written onto the reservation: "
                + "bookingIds={} intentId={}", bookingIds, paymentIntentId, e);
        }
    }

    private void reportOutcomeUnknown(List<UUID> bookingIds, UUID batchId, String chargeKey) {
        Counter.builder(SETTLE_ABORTED_COUNTER)
            .tag("reason", "charge_outcome_unknown")
            .register(meterRegistry)
            .increment();
        log.error("[STRIPE_CHARGE_OUTCOME_UNKNOWN] bookingIds={} batchId={} key={} — the card may have been charged; "
            + "reservations kept CAPTURE_PENDING for ChargeOutcomeReconciler, nothing declined", bookingIds, batchId,
            chargeKey);
    }

    // ─── Batch booking ────────────────────────────────────────────────────────

    // REQUIRES_NEW, matching onBookingAccepted above: during AFTER_COMMIT the accept transaction is
    // still bound to the thread but already committed, so the nested @Transactional calls in
    // PackSessionService/BookingPaymentPersistenceService would join a completed transaction and
    // lose their writes. Without this the batch bookings settle in memory and stay PAYMENT_PENDING
    // in the database (found while proving Deferred-12 AC6 end-to-end; BatchPaymentIT never caught
    // it because it invokes this listener directly, with no surrounding transaction).
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBatchBookingAccepted(BatchBookingAcceptedEvent event) {
        List<UUID> packIds = new ArrayList<>();
        List<UUID> creditIds = new ArrayList<>();

        for (UUID bookingId : event.getAcceptedBookingIds()) {
            bookingRepository.findById(bookingId).ifPresent(b -> {
                if (b.getSessionPackPurchaseId() != null) {
                    packIds.add(bookingId);
                } else {
                    creditIds.add(bookingId);
                }
            });
        }

        // Process pack-based bookings first
        for (UUID bookingId : packIds) {
            // Behaviour unchanged in practice — pack settlement never reserves — but the same
            // helper is used so no second reading of "a row exists" survives in this file.
            if (isSettled(bookingId)) continue;
            Booking b = bookingRepository.findById(bookingId).orElse(null);
            if (b == null) continue;
            try {
                // Deduct + confirm in one transaction of this booking's own, so the two are atomic
                // and a failure cannot mark a sibling's transaction rollback-only. The rollback also
                // undoes the deduction, which is why no manual restoreSession compensation is needed.
                perBookingTx.executeWithoutResult(s -> {
                    packSessionService.deductSession(b.getSessionPackPurchaseId());
                    persistenceService.confirmPackBatchPayment(bookingId, event.getBatchId(),
                        event.getParentId(), event.getParentEmail(), event.getCoachDisplayName(),
                        b.getRequestedStartTime(), b.getCanonicalTimezone());
                });
            } catch (Exception e) {
                log.error("Pack session deduction failed in batch: bookingId={} batchId={}", bookingId, event.getBatchId());
                persistenceService.declineBatchBooking(bookingId, event.getBatchId());
            }
        }

        if (creditIds.isEmpty()) return;

        // UAT.3 AC3. Reserve BEFORE pricing, and price only what actually holds a reservation.
        // Charging for a booking that failed to reserve — because it was cancelled, already
        // settled, or left a reservation behind from a previous attempt — is the exact defect this
        // step exists to prevent.
        //
        // DEADLOCK CONSTRAINT: reserveCapture is REQUIRES_NEW and locks booking.bookings on a
        // SECOND connection, while this transaction is open on the first. Verified at the time of
        // writing that this transaction holds no lock and no uncommitted write on these rows: it
        // only issues plain findById reads, and the pack loop above writes exclusively through
        // perBookingTx (REQUIRES_NEW), which has already committed. If a future edit adds a write
        // here, it deadlocks with NO timeout — a plain UPDATE ignores the
        // jakarta.persistence.lock.timeout=0 (NOWAIT) hint.
        List<UUID> reserved = new ArrayList<>();
        Map<UUID, BigDecimal> reservedPrices = new HashMap<>();
        BigDecimal creditSubtotal = BigDecimal.ZERO;
        for (UUID bookingId : creditIds) {
            Booking b = bookingRepository.findById(bookingId).orElse(null);
            if (b == null) continue;
            BigDecimal price = batchBookingPrice(b);
            if (price == null) {
                // fix/payment-agreed-price-lock: used to settle at .orElse(ZERO), i.e. confirm for free.
                // acceptOneBooking now refuses an unpriceable booking, so this is a backstop: decline it
                // before any reservation or charge (nothing was taken).
                Counter.builder(SETTLE_ABORTED_COUNTER).tag("reason", "unpriced").register(meterRegistry).increment();
                log.error("Batch booking has no agreed price — declined, nothing charged: "
                    + "bookingId={} batchId={}", bookingId, event.getBatchId());
                declineQuietly(bookingId, event.getBatchId());
                continue;
            }
            // Intended credit is not known per booking until the wallet split below, so the whole
            // price is reserved under stripeCharged and the settle write-back corrects both
            // columns. A reserved row's amounts are an operator's reconciliation hint, not an
            // accounting record — only CAPTURED rows are ever summed.
            CaptureReservation reservation = reserveOrReport(
                bookingId, BigDecimal.ZERO, price, event.getBatchId(), null);
            if (reservation != CaptureReservation.RESERVED) {
                // A booking that could not be reserved — for any reason, including a thrown one —
                // is dropped from the batch and its siblings carry on. Letting the exception escape
                // would abandon every booking already reserved above in CAPTURE_PENDING, each then
                // needing manual Stripe reconciliation, to punish one booking's failure.
                if (reservation != null) {
                    abortSettle(bookingId, reservation);
                }
                continue;
            }
            reserved.add(bookingId);
            reservedPrices.put(bookingId, price);
            creditSubtotal = creditSubtotal.add(price);
        }

        // Nothing holds a reservation: return before the wallet read, the ledger entry and the
        // charge, so a batch of already-cancelled bookings cannot debit the parent.
        if (reserved.isEmpty()) return;

        // feat/coach-workflow-gaps A22: the split rule lives in CreditCardSplit so the parent's pre-submit
        // batch quote (BatchPaymentQuoteService) computes exactly this figure.
        BigDecimal balance = walletBalanceForSettlement(event.getParentId());
        CreditCardSplit split = CreditCardSplit.of(balance, creditSubtotal, paymentGateway.minimumCardCharge());
        BigDecimal creditToUse = split.creditApplied();
        BigDecimal stripeAmount = split.cardCharge();

        // Each booking's credit / card share, decided once before the charge. fix/payment-agreed-price-lock:
        // the price reserved above (agreed_price), so the shares always sum to exactly what is charged.
        List<BookingPaymentPersistenceService.ChargeShare> shares = new ArrayList<>();
        BigDecimal remainingCredit = creditToUse;
        for (UUID bookingId : reserved) {
            BigDecimal price = reservedPrices.get(bookingId);
            BigDecimal creditShare = remainingCredit.signum() > 0 ? price.min(remainingCredit) : BigDecimal.ZERO;
            remainingCredit = remainingCredit.subtract(creditShare);
            shares.add(new BookingPaymentPersistenceService.ChargeShare(bookingId, creditShare, price.subtract(creditShare)));
        }

        String paymentIntentId = null;
        if (stripeAmount.compareTo(BigDecimal.ZERO) > 0) {
            // fix/payment-charge-idempotency (H1/H2): the key names THIS settlement — batch id + the exact
            // reserved set — so a concurrent accept of the same batch (a disjoint set) cannot replay this
            // PaymentIntent. It and every row's share are committed onto the reservations before the call.
            String chargeKey = ChargeKeys.forBatch(event.getBatchId(), reserved);
            try {
                persistenceService.stampBatchCharge(shares, chargeKey);
            } catch (RuntimeException e) {
                // Nothing charged yet: decline outright rather than charge under a key no row records.
                log.error("Batch charge key could not be recorded — declining, nothing charged: batchId={}",
                    event.getBatchId(), e);
                reserved.forEach(id -> declineQuietly(id, event.getBatchId()));
                return;
            }
            try {
                paymentIntentId = paymentGateway.chargeAndCaptureForBatch(
                    event.getBatchId(), event.getParentId(), event.getCoachId(), stripeAmount, chargeKey);
            } catch (ChargeOutcomeUnknownException e) {
                // The card may have been charged: decline nothing. No credit was debited (it commits per
                // booking with the confirm), and the rows carry the key and their shares for the reconciler.
                reportOutcomeUnknown(reserved, event.getBatchId(), chargeKey);
                return;
            } catch (PaymentGatewayException e) {
                log.error("Stripe batch charge failed: batchId={}", event.getBatchId());
                // UAT.3 AC3. Every reserved booking now HAS a row, so the previous
                // `if (!existsById(bookingId))` condition would be permanently false and nothing
                // would be declined — the whole batch would strand in PAYMENT_PENDING. Decline the
                // reserved set outright; declineBatchBooking writes each existing row to
                // CHARGE_FAILED through the AC3 write-back helper. No credit to restore: none was debited.
                for (UUID bookingId : reserved) {
                    persistenceService.declineBatchBooking(bookingId, event.getBatchId());
                }
                return;
            }
            recordChargeIntentQuietly(reserved, paymentIntentId);
        }

        String finalPaymentIntentId = paymentIntentId;
        for (BookingPaymentPersistenceService.ChargeShare share : shares) {
            UUID bookingId = share.bookingId();
            final BigDecimal creditShare = share.credit();
            final BigDecimal stripeShare = share.card();
            final Booking b;
            try {
                b = isSettled(bookingId) ? null : bookingRepository.findById(bookingId).orElse(null);
            } catch (RuntimeException e) {
                // fix/payment-charge-idempotency: used to escape and abandon every later sibling. The row stays
                // CAPTURE_PENDING (with the charge key and intent id when a charge was made) for the reconciler.
                log.error("[BATCH_SETTLE_READ_FAILED] bookingId={} batchId={} — left CAPTURE_PENDING", bookingId,
                    event.getBatchId(), e);
                continue;
            }
            if (b == null) {
                // fix/payment-agreed-price-lock: this booking's card share was already charged above. It used to
                // be skipped silently, keeping the money; credit it back instead. Its payment row is left alone
                // (something else settled it, or the booking is gone). fix/payment-charge-idempotency: only the
                // card share — its credit share was never debited.
                log.error("Batch booking settled elsewhere or vanished after the batch charge — crediting its "
                        + "card share back to the wallet: bookingId={} batchId={} card={}",
                    bookingId, event.getBatchId(), stripeShare);
                refundShareQuietly(event.getParentId(), bookingId, event.getBatchId(), stripeShare,
                    finalPaymentIntentId, stripeShare);
                continue;
            }
            try {
                // Own transaction per booking, as in the pack loop above: one booking failing to settle must
                // not discard the siblings that already succeeded. fix/payment-charge-idempotency (M2): the
                // booking's credit share is debited in this same transaction (reference = bookingId), so a
                // confirmed booking always has its deduction and a crash between bookings strands none. The
                // listener still holds the payer's wallet lock; parent_credit_ledger has no FK to
                // stripe_customers, so this second connection cannot block on it.
                perBookingTx.executeWithoutResult(s -> {
                    if (creditShare.signum() > 0) {
                        // fix/payment-wallet-dispute-integrity: spend-only credit is consumed first (each booking's
                        // split reads the pools after the previous booking's deduction committed).
                        creditWalletService.debitForBooking(event.getParentId(), creditShare, bookingId,
                            "Batch session booking deduction");
                    }
                    persistenceService.confirmCreditBatchPayment(bookingId, event.getBatchId(),
                        creditShare, stripeShare, finalPaymentIntentId,
                        event.getParentId(), event.getParentEmail(), event.getCoachDisplayName(),
                        b.getRequestedStartTime(), b.getCanonicalTimezone());
                });
            } catch (Exception e) {
                log.error("Credit batch settle failed after the batch charge: bookingId={} batchId={}",
                    bookingId, event.getBatchId(), e);
                if (stripeShare.signum() == 0) {
                    // Paid wholly from credit, and that debit rolled back with the confirm: nothing was taken.
                    declineQuietly(bookingId, event.getBatchId());
                    continue;
                }
                // fix/payment-agreed-price-lock: the parent WAS charged this booking's card share, so declining
                // it with a zeroed CHARGE_FAILED row kept their money. Decline it with the real amount and credit
                // the card share back to the wallet, atomically.
                try {
                    persistenceService.declineBatchBookingAfterCharge(bookingId, event.getBatchId(),
                        BigDecimal.ZERO, stripeShare, finalPaymentIntentId, event.getParentId());
                } catch (RuntimeException de) {
                    // Nothing committed: the reserved CAPTURE_PENDING row survives with its charge key and intent
                    // id, so ChargeOutcomeReconciler retries it. Do not abandon siblings.
                    log.error("[BATCH_DECLINE_REFUND_FAILED] bookingId={} batchId={} parentId={} card={} — decline "
                            + "and refund both rolled back; left for ChargeOutcomeReconciler",
                        bookingId, event.getBatchId(), event.getParentId(), stripeShare, de);
                }
            }
        }
    }

    /**
     * fix/payment-agreed-price-lock: what a non-pack batch booking settles at — its {@code agreed_price},
     * else null (unpriceable). fix/booking-price-and-excused-race: the coach's current price is no longer a
     * fallback (the payer never saw it); acceptOneBooking already refuses such a booking (PRICE_NOT_LOCKED),
     * so null here is only the backstop's trigger.
     */
    private BigDecimal batchBookingPrice(Booking b) {
        return b.getAgreedPrice();
    }

    private void declineQuietly(UUID bookingId, UUID batchId) {
        try {
            persistenceService.declineBatchBooking(bookingId, batchId);
        } catch (RuntimeException e) {
            log.error("Declining batch booking failed (nothing was charged for it): bookingId={} batchId={}",
                bookingId, batchId, e);
        }
    }

    private void refundShareQuietly(Long parentId, UUID bookingId, UUID batchId, BigDecimal amount,
                                    String paymentIntentId, BigDecimal cardShare) {
        if (amount.signum() <= 0) return;
        try {
            persistenceService.refundBatchShareToCredit(parentId, bookingId, batchId, amount, paymentIntentId,
                cardShare);
        } catch (RuntimeException e) {
            log.error("[BATCH_DECLINE_REFUND_FAILED] bookingId={} batchId={} parentId={} amount={} — refund "
                + "not enqueued; credit by hand", bookingId, batchId, parentId, amount, e);
        }
    }
}
