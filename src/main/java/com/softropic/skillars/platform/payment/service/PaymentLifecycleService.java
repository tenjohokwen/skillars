package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.BatchBookingAcceptedEvent;
import com.softropic.skillars.platform.booking.contract.BookingAcceptedEvent;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachPricingRepository;
import com.softropic.skillars.platform.payment.contract.BookingPaymentStatus;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.List;
import java.util.Locale;
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
    private final CoachPricingRepository coachPricingRepository;
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
     * bounded PESSIMISTIC_WRITE lock (a 5 s timeout surfaces as PessimisticLockingFailureException)
     * and inserts against a primary key.
     *
     * <p>Returning null rather than rethrowing is deliberate. Nothing has been charged — the
     * reservation is what gates the gateway call — so the booking simply rests in PAYMENT_PENDING
     * with no payment row, which is precisely the state PaymentPendingSweeper can decide safely.
     */
    private CaptureReservation reserveOrReport(UUID bookingId, BigDecimal intendedCredit,
                                               BigDecimal intendedStripe, UUID batchId) {
        try {
            return persistenceService.reserveCapture(bookingId, intendedCredit, intendedStripe, batchId);
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
        } catch (RuntimeException e) {
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
        BigDecimal balance = creditWalletService.getBalance(event.getParentId());
        BigDecimal creditToUse = balance.min(event.getSessionPrice());
        BigDecimal stripeAmount = creditToUse.compareTo(event.getSessionPrice()) >= 0
            ? BigDecimal.ZERO
            : event.getSessionPrice().subtract(creditToUse);

        String paymentIntentId = null;
        if (stripeAmount.compareTo(BigDecimal.ZERO) > 0) {
            // UAT.3 AC3. Reserve only when a Stripe call is actually about to happen: a fully
            // credit-covered booking touches Stripe never, and a row from it would break the
            // invariant "no row ⇒ no charge was attempted" that the sweeper reads.
            //
            // DEADLOCK CONSTRAINT: reserveCapture is REQUIRES_NEW and takes a PESSIMISTIC_WRITE
            // lock on booking.bookings on a SECOND connection. This transaction must therefore
            // hold no lock and no uncommitted write on that booking row here — today it only
            // issues reads. If a future edit adds a write above this line, it deadlocks with NO
            // timeout: a plain UPDATE ignores the 5 s lock.timeout hint, which applies only to the
            // locked SELECT.
            CaptureReservation reservation = reserveOrReport(
                event.getBookingId(), creditToUse, stripeAmount, null);
            if (reservation != CaptureReservation.RESERVED) {
                if (reservation != null) {
                    abortSettle(event.getBookingId(), reservation);
                }
                return;
            }
            try {
                paymentIntentId = paymentGateway.chargeAndCapture(
                    event.getBookingId(), event.getParentId(), event.getCoachId(), stripeAmount);
            } catch (PaymentGatewayException e) {
                log.error("Stripe charge failed for booking {}: {}", event.getBookingId(), e.getMessage());
                persistenceService.persistPaymentFailure(event.getBookingId(), BigDecimal.ZERO,
                    event.getParentId(), event.getParentEmail(), event.getCoachDisplayName(),
                    event.getRequestedStartTime(), event.getCanonicalTimezone());
                return;
            }
        }

        // BOOKING_DEDUCTION + BookingPayment + status transition all commit atomically
        persistenceService.persistPaymentSuccess(event.getBookingId(), creditToUse, stripeAmount,
            paymentIntentId, null, event.getParentId(), event.getParentEmail(), event.getCoachDisplayName(),
            event.getRequestedStartTime(), event.getCanonicalTimezone());
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
        // here, it deadlocks with NO timeout — a plain UPDATE ignores the 5 s lock.timeout hint.
        List<UUID> reserved = new ArrayList<>();
        BigDecimal creditSubtotal = BigDecimal.ZERO;
        for (UUID bookingId : creditIds) {
            Booking b = bookingRepository.findById(bookingId).orElse(null);
            if (b == null) continue;
            BigDecimal price = coachPricingRepository.findByCoachId(b.getCoachId())
                .map(p -> p.getPerSessionPrice())
                .orElse(BigDecimal.ZERO);
            // Intended credit is not known per booking until the wallet split below, so the whole
            // price is reserved under stripeCharged and the settle write-back corrects both
            // columns. A reserved row's amounts are an operator's reconciliation hint, not an
            // accounting record — only CAPTURED rows are ever summed.
            CaptureReservation reservation = reserveOrReport(
                bookingId, BigDecimal.ZERO, price, event.getBatchId());
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
            creditSubtotal = creditSubtotal.add(price);
        }

        // Nothing holds a reservation: return before the wallet read, the ledger entry and the
        // charge, so a batch of already-cancelled bookings cannot debit the parent.
        if (reserved.isEmpty()) return;

        BigDecimal balance = creditWalletService.getBalance(event.getParentId());
        BigDecimal creditToUse = balance.min(creditSubtotal);
        BigDecimal stripeAmount = creditSubtotal.subtract(creditToUse);

        if (creditToUse.compareTo(BigDecimal.ZERO) > 0) {
            creditWalletService.writeLedgerEntry(event.getParentId(), creditToUse.negate(),
                "BOOKING_DEDUCTION", event.getBatchId(), "Batch session booking deduction");
        }

        String paymentIntentId = null;
        if (stripeAmount.compareTo(BigDecimal.ZERO) > 0) {
            try {
                paymentIntentId = paymentGateway.chargeAndCaptureForBatch(
                    event.getBatchId(), event.getParentId(), event.getCoachId(), stripeAmount);
            } catch (PaymentGatewayException e) {
                log.error("Stripe batch charge failed: batchId={}", event.getBatchId());
                if (creditToUse.compareTo(BigDecimal.ZERO) > 0) {
                    creditWalletService.writeLedgerEntry(event.getParentId(), creditToUse,
                        "BOOKING_DEDUCTION_REVERSAL", event.getBatchId(), "Batch payment failed - credit restored");
                }
                // UAT.3 AC3. Every reserved booking now HAS a row, so the previous
                // `if (!existsById(bookingId))` condition would be permanently false and nothing
                // would be declined — the whole batch would strand in PAYMENT_PENDING. Decline the
                // reserved set outright; declineBatchBooking writes each existing row to
                // CHARGE_FAILED through the AC3 write-back helper.
                for (UUID bookingId : reserved) {
                    persistenceService.declineBatchBooking(bookingId, event.getBatchId());
                }
                return;
            }
        }

        String finalPaymentIntentId = paymentIntentId;
        BigDecimal remainingCredit = creditToUse;
        for (UUID bookingId : reserved) {
            if (isSettled(bookingId)) continue;
            Booking b = bookingRepository.findById(bookingId).orElse(null);
            if (b == null) continue;
            BigDecimal price = coachPricingRepository.findByCoachId(b.getCoachId())
                .map(p -> p.getPerSessionPrice()).orElse(BigDecimal.ZERO);
            BigDecimal bookingCreditShare = remainingCredit.compareTo(BigDecimal.ZERO) > 0
                ? price.min(remainingCredit) : BigDecimal.ZERO;
            remainingCredit = remainingCredit.subtract(bookingCreditShare);
            BigDecimal bookingStripeShare = price.subtract(bookingCreditShare);
            final BigDecimal creditShare = bookingCreditShare;
            final BigDecimal stripeShare = bookingStripeShare;
            try {
                // Own transaction per booking, as in the pack loop above: one booking failing to
                // settle must not discard the siblings that already succeeded.
                perBookingTx.executeWithoutResult(s ->
                    persistenceService.confirmCreditBatchPayment(bookingId, event.getBatchId(),
                        creditShare, stripeShare, finalPaymentIntentId,
                        event.getParentId(), event.getParentEmail(), event.getCoachDisplayName(),
                        b.getRequestedStartTime(), b.getCanonicalTimezone()));
            } catch (Exception e) {
                log.error("Credit batch settle failed: bookingId={} batchId={}", bookingId, event.getBatchId());
                persistenceService.declineBatchBooking(bookingId, event.getBatchId());
            }
        }
    }
}
