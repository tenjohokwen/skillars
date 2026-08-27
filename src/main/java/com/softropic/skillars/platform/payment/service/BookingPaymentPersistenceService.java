package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.ActorRole;
import com.softropic.skillars.platform.booking.contract.BookingConfirmedEvent;
import com.softropic.skillars.platform.booking.contract.BookingDeclinedEvent;
import com.softropic.skillars.platform.booking.contract.BookingEvent;
import com.softropic.skillars.platform.booking.contract.BookingStateTransitionException;
import com.softropic.skillars.platform.booking.contract.TransitionContext;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.payment.contract.BookingPaymentStatus;
import com.softropic.skillars.platform.payment.repo.BookingPayment;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingPaymentPersistenceService {

    private static final String SETTLE_CONFLICT_COUNTER = "booking.payment.settle_conflict";
    private static final String SETTLE_ERROR_COUNTER = "booking.payment.settle_error";
    private static final String SETTLE_SUCCESS_COUNTER = "booking.payment.settle_success";
    private static final String SETTLE_FAILED_COUNTER = "booking.payment.settle_failed";

    private final CreditWalletService creditWalletService;
    private final BookingPaymentRepository bookingPaymentRepository;
    private final BookingRepository bookingRepository;
    private final BookingService bookingService;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final PessimisticLockRetryer lockRetryer;

    private Counter settleConflictCounter;
    private Counter settleErrorCounter;
    private Counter settleSuccessCounter;
    private Counter settleFailedCounter;

    @jakarta.annotation.PostConstruct
    void initializeCounters() {
        settleConflictCounter = Counter.builder(SETTLE_CONFLICT_COUNTER).register(meterRegistry);
        settleErrorCounter = Counter.builder(SETTLE_ERROR_COUNTER).register(meterRegistry);
        settleSuccessCounter = Counter.builder(SETTLE_SUCCESS_COUNTER).register(meterRegistry);
        settleFailedCounter = Counter.builder(SETTLE_FAILED_COUNTER).register(meterRegistry);
    }

    /**
     * UAT.3 AC1. Writes a CAPTURE_PENDING {@code booking_payments} row BEFORE the caller contacts
     * Stripe, establishing the invariant the sweeper and the parent-cancel interlock both rest on:
     *
     * <p><strong>For a booking still in PAYMENT_PENDING, the absence of a
     * {@code payment.booking_payments} row proves no Stripe call has been attempted for it.</strong>
     *
     * <p>It holds because the only two Stripe-charging call sites for bookings
     * ({@code PaymentLifecycleService.handleCreditBasedBooking} and {@code onBatchBookingAccepted})
     * are gated behind this method, and every other settlement path never calls Stripe at all.
     * Pack-funded settlement and fully-credit-covered settlement deliberately do NOT reserve — a row
     * from them would mean "no charge was attempted" no longer follows from "no row".
     *
     * <p>{@code REQUIRES_NEW} is load-bearing, and so is what this method does not touch. It must
     * commit independently of the caller: a row that rolls back with the caller's transaction is
     * worth nothing, since that transaction rolling back is the exact scenario being defended
     * against. And because {@code REQUIRES_NEW} runs on a <em>second pooled connection</em>, this
     * method touches only {@code booking.bookings} (the locked read) and
     * {@code payment.booking_payments}. If a caller's transaction ever held a lock or an
     * uncommitted write on the same booking row, this would self-deadlock against its own caller —
     * and a plain {@code UPDATE} ignores the 5 s {@code lock.timeout} hint, so it would hang rather
     * than fail. Both call sites are annotated with that constraint; verify it before adding a
     * third.
     *
     * <p>The {@code booking_payments} read below is deliberately unlocked: two concurrent
     * reservations for the same booking serialise on the booking-row lock taken first, so the
     * read-then-insert is protected by it, with {@code pk_booking_payments} as the backstop.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CaptureReservation reserveCapture(UUID bookingId, BigDecimal intendedCredit,
                                             BigDecimal intendedStripe, UUID batchId) {
        Booking booking = lockRetryer.withBoundedRetry(() -> bookingRepository.findByIdForUpdate(bookingId).orElse(null));
        if (booking == null) {
            return CaptureReservation.BOOKING_NOT_PENDING;
        }
        // Compared as a string exactly as PaymentPendingSweeper.sweepOne does: BookingService's
        // readStatusOrThrow is private and throws a 404-shaped exception on an unrecognised status.
        if (!"PAYMENT_PENDING".equals(booking.getStatus())) {
            // The branch that makes a cancel which won the race cost nothing: the caller returns
            // before touching Stripe.
            return CaptureReservation.BOOKING_NOT_PENDING;
        }

        Optional<BookingPayment> existing = bookingPaymentRepository.findById(bookingId);
        if (existing.isPresent()) {
            return BookingPaymentStatus.CAPTURE_PENDING.equals(existing.get().getStatus())
                ? CaptureReservation.CAPTURE_UNCONFIRMED
                : CaptureReservation.ALREADY_SETTLED;
        }

        BookingPayment bp = new BookingPayment();
        bp.setBookingId(bookingId);
        bp.setCreditDebited(intendedCredit);
        bp.setStripeCharged(intendedStripe);
        bp.setBatchPaymentIntentId(batchId);
        bp.setStripePaymentIntentId(null);
        bp.setStatus(BookingPaymentStatus.CAPTURE_PENDING);
        bp.setCapturedAt(null);
        bookingPaymentRepository.save(bp);
        return CaptureReservation.RESERVED;
    }

    /**
     * UAT.3 AC3. Every writer of {@code booking_payments} goes through this, because a reserved row
     * may already exist: {@code new BookingPayment()} + {@code save()} is a {@code merge()} rather
     * than an insert for an assigned {@code @Id}, which would silently overwrite {@code frozenAt}
     * and every other column the fresh object left null.
     */
    private BookingPayment loadOrCreate(UUID bookingId) {
        BookingPayment bp = bookingPaymentRepository.findById(bookingId).orElseGet(BookingPayment::new);
        bp.setBookingId(bookingId);
        return bp;
    }

    /**
     * UAT.3 AC4. Every settle-side transition runs inside an AFTER_COMMIT listener, where a
     * {@link BookingStateTransitionException} produced no application-level ERROR and no meter at
     * all — the failure was completely silent. AC1–AC3 make the known route here unreachable; this
     * exists for the routes nobody has found yet.
     *
     * <p>Rethrows deliberately. Rolling the settle transaction back is correct, and the reserved
     * CAPTURE_PENDING row survives it because {@code reserveCapture} committed it in its own
     * transaction — that surviving row is exactly the durable signal PaymentPendingSweeper
     * escalates on. Swallowing would commit a half-settled state.
     *
     * <p>Micrometer counters are not transactional, so the increment survives the rollback. That is
     * intended: the alert must fire even though the write does not land.
     */
    private void transitionOrReport(UUID bookingId, BookingEvent event) {
        try {
            bookingService.transition(bookingId, event, new TransitionContext(ActorRole.SYSTEM, null));
        } catch (BookingStateTransitionException e) {
            settleConflictCounter.increment();
            log.error("Settle transition rejected: bookingId={} statusReadFrom={} event={} — the "
                    + "booking moved underneath the settlement and this settle will roll back",
                bookingId, statusOf(bookingId), event, e);
            throw e;
        } catch (RuntimeException e) {
            // A state-machine rejection is the expected way to fail here and gets its own counter
            // above. Everything else — a vanished booking, an optimistic-lock clash, a constraint
            // violation — was equally silent before, and this AC is about the routes nobody has
            // found yet, so catching only the anticipated one would leave that purpose half-met.
            settleErrorCounter.increment();
            log.error("Settle transition failed unexpectedly: bookingId={} statusReadFrom={} event={} — "
                    + "this settle will roll back", bookingId, statusOf(bookingId), event, e);
            throw e;
        }
    }

    /**
     * Best-effort, and deliberately so: this only ever runs on a path that is already failing, and
     * the read itself can throw once the transaction is doomed. Losing the log line because the
     * diagnostic read failed would be the opposite of the point.
     */
    private String statusOf(UUID bookingId) {
        try {
            return bookingRepository.findById(bookingId).map(Booking::getStatus).orElse("ABSENT");
        } catch (RuntimeException e) {
            return "UNREADABLE";
        }
    }

    /**
     * Writes BOOKING_DEDUCTION + BookingPayment(CAPTURED) + transitions booking CONFIRMED
     * in a single @Transactional — credit deduction only commits when the payment record and
     * status transition also commit (P3 fix: deduction is no longer pre-committed before Stripe).
     */
    @Transactional
    public void persistPaymentSuccess(UUID bookingId, BigDecimal creditDebited, BigDecimal stripeCharged,
                                       String paymentIntentId, UUID batchPaymentIntentId,
                                       Long parentId, String parentEmail, String coachDisplayName,
                                       Instant requestedStartTime, String canonicalTimezone) {
        settleSuccessCounter.increment();
        if (creditDebited.compareTo(BigDecimal.ZERO) > 0) {
            creditWalletService.writeLedgerEntry(parentId, creditDebited.negate(),
                "BOOKING_DEDUCTION", bookingId, "Session booking deduction");
        }
        BookingPayment bp = loadOrCreate(bookingId);
        bp.setCreditDebited(creditDebited);
        bp.setStripeCharged(stripeCharged);
        bp.setStripePaymentIntentId(paymentIntentId);
        bp.setBatchPaymentIntentId(batchPaymentIntentId);
        bp.setStatus(BookingPaymentStatus.CAPTURED);
        bp.setCapturedAt(Instant.now());
        bookingPaymentRepository.save(bp);
        transitionOrReport(bookingId, BookingEvent.PAYMENT_CAPTURED);
        eventPublisher.publishEvent(BookingConfirmedEvent.builder()
            .source(this)
            .bookingId(bookingId)
            .parentId(parentId)
            .parentEmail(parentEmail)
            .coachDisplayName(coachDisplayName)
            .requestedStartTime(requestedStartTime)
            .canonicalTimezone(canonicalTimezone)
            .build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistPaymentFailure(UUID bookingId, BigDecimal creditToReverse,
                                       Long parentId, String parentEmail, String coachDisplayName,
                                       Instant requestedStartTime, String canonicalTimezone) {
        settleFailedCounter.increment();
        if (creditToReverse.compareTo(BigDecimal.ZERO) > 0) {
            creditWalletService.writeLedgerEntry(parentId, creditToReverse,
                "BOOKING_DEDUCTION_REVERSAL", bookingId, "Payment failed - credit restored");
        }
        BookingPayment bp = loadOrCreate(bookingId);
        bp.setCreditDebited(BigDecimal.ZERO);
        bp.setStripeCharged(BigDecimal.ZERO);
        bp.setStatus(BookingPaymentStatus.CHARGE_FAILED);
        bookingPaymentRepository.save(bp);
        transitionOrReport(bookingId, BookingEvent.PAYMENT_FAILED);
        eventPublisher.publishEvent(new BookingDeclinedEvent(
            this, bookingId, parentId, parentEmail, coachDisplayName, requestedStartTime, canonicalTimezone));
    }

    @Transactional
    public void confirmPackBatchPayment(UUID bookingId, UUID batchId, Long parentId, String parentEmail,
                                         String coachDisplayName, Instant requestedStartTime,
                                         String canonicalTimezone) {
        BookingPayment bp = loadOrCreate(bookingId);
        bp.setBatchPaymentIntentId(batchId);
        bp.setCreditDebited(BigDecimal.ZERO);
        bp.setStripeCharged(BigDecimal.ZERO);
        bp.setStatus(BookingPaymentStatus.CAPTURED);
        bp.setCapturedAt(Instant.now());
        bookingPaymentRepository.save(bp);
        transitionOrReport(bookingId, BookingEvent.PAYMENT_CAPTURED);
        eventPublisher.publishEvent(BookingConfirmedEvent.builder()
            .source(this)
            .bookingId(bookingId)
            .parentId(parentId)
            .parentEmail(parentEmail)
            .coachDisplayName(coachDisplayName)
            .requestedStartTime(requestedStartTime)
            .canonicalTimezone(canonicalTimezone)
            .build());
    }

    @Transactional
    public void confirmCreditBatchPayment(UUID bookingId, UUID batchId, BigDecimal creditDebited,
                                           BigDecimal stripeCharged, String paymentIntentId,
                                           Long parentId, String parentEmail, String coachDisplayName,
                                           Instant requestedStartTime, String canonicalTimezone) {
        BookingPayment bp = loadOrCreate(bookingId);
        bp.setBatchPaymentIntentId(batchId);
        bp.setCreditDebited(creditDebited);
        bp.setStripeCharged(stripeCharged);
        bp.setStripePaymentIntentId(paymentIntentId);
        bp.setStatus(BookingPaymentStatus.CAPTURED);
        bp.setCapturedAt(Instant.now());
        bookingPaymentRepository.save(bp);
        transitionOrReport(bookingId, BookingEvent.PAYMENT_CAPTURED);
        eventPublisher.publishEvent(BookingConfirmedEvent.builder()
            .source(this)
            .bookingId(bookingId)
            .parentId(parentId)
            .parentEmail(parentEmail)
            .coachDisplayName(coachDisplayName)
            .requestedStartTime(requestedStartTime)
            .canonicalTimezone(canonicalTimezone)
            .build());
    }

    /**
     * REQUIRES_NEW, unlike the confirm* methods: this only ever runs from the batch listener's
     * catch block, i.e. after that booking's settle attempt has already thrown and rolled back its
     * own transaction. Its own transaction keeps the DECLINED write independent of both the failed
     * settle and the listener's surrounding transaction, so the booking is never left stranded in
     * PAYMENT_PENDING with no record of why.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void declineBatchBooking(UUID bookingId, UUID batchId) {
        BookingPayment bp = loadOrCreate(bookingId);
        bp.setBatchPaymentIntentId(batchId);
        bp.setCreditDebited(BigDecimal.ZERO);
        bp.setStripeCharged(BigDecimal.ZERO);
        bp.setStatus(BookingPaymentStatus.CHARGE_FAILED);
        bookingPaymentRepository.save(bp);
        transitionOrReport(bookingId, BookingEvent.PAYMENT_FAILED);
    }
}
