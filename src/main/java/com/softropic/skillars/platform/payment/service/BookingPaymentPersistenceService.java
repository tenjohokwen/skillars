package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.config.DataSourceConfig;
import com.softropic.skillars.infrastructure.config.RoutingDataSourceContext;
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
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.payment.contract.BookingPaymentStatus;
import com.softropic.skillars.platform.payment.repo.BookingPayment;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
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
    private final ConfigService configService;
    private final RefundOutboxSupport refundOutboxSupport;

    /**
     * skillars-deferred-137 AC2: self-reference so {@code reserveCapture}/{@code persistPaymentFailure}/
     * {@code declineBatchBooking} can set {@link RoutingDataSourceContext} BEFORE their own {@code
     * @Transactional(REQUIRES_NEW)} proxy advice opens (and acquires a connection for) the actual
     * transactional method — a routing key set inside an already-{@code @Transactional} method's own
     * body runs too late, since the Spring AOP proxy already acquired the connection before the method
     * body starts executing. Mirrors {@code GdprErasureService.self}/{@code PackSessionService.self}'s
     * identical pattern.
     */
    @Autowired
    @Lazy
    private BookingPaymentPersistenceService self;

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
     * and a plain {@code UPDATE} ignores the {@code jakarta.persistence.lock.timeout=0} (NOWAIT)
     * hint {@code findByIdForUpdate} relies on, so it would hang rather than fail fast. Both call
     * sites are annotated with that constraint; verify it before adding a third.
     *
     * <p>The {@code booking_payments} read below is deliberately unlocked: two concurrent
     * reservations for the same booking serialise on the booking-row lock taken first, so the
     * read-then-insert is protected by it, with {@code pk_booking_payments} as the backstop.
     *
     * <p>skillars-deferred-137 AC2: thin, non-transactional wrapper around {@link
     * #reserveCaptureTransactional} — required so {@link RoutingDataSourceContext} is set BEFORE
     * that method's own {@code @Transactional(REQUIRES_NEW)} proxy advice opens its connection (see
     * {@link #self}'s own Javadoc), routing this method's {@code REQUIRES_NEW} acquisition to the
     * dedicated payment pool instead of the primary one. Mirrors {@code
     * GdprErasureService.erase}/{@code eraseTransactional}'s own established self-invocation split.
     */
    public CaptureReservation reserveCapture(UUID bookingId, BigDecimal intendedCredit,
                                             BigDecimal intendedStripe, UUID batchId) {
        return reserveCapture(bookingId, intendedCredit, intendedStripe, batchId, null);
    }

    /**
     * fix/payment-charge-idempotency: as {@link #reserveCapture(UUID, BigDecimal, BigDecimal, UUID)}, also
     * writing the Stripe idempotency key the charge will be sent under, so it is durable BEFORE the call —
     * {@code ChargeOutcomeReconciler} finds the PaymentIntent from it if the response is lost. A batch passes
     * null here (its key names the whole reserved set, known only once every booking is reserved) and stamps
     * it with {@link #stampBatchCharge}.
     */
    public CaptureReservation reserveCapture(UUID bookingId, BigDecimal intendedCredit,
                                             BigDecimal intendedStripe, UUID batchId, String chargeKey) {
        RoutingDataSourceContext.set(DataSourceConfig.PAYMENT_REQUIRES_NEW_DATASOURCE_KEY);
        try {
            return self.reserveCaptureTransactional(bookingId, intendedCredit, intendedStripe, batchId, chargeKey);
        } finally {
            RoutingDataSourceContext.clear();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CaptureReservation reserveCaptureTransactional(UUID bookingId, BigDecimal intendedCredit,
                                             BigDecimal intendedStripe, UUID batchId, String chargeKey) {
        Booking booking = lockRetryer.withBoundedRetry("BookingPaymentPersistenceService.reserveCapture",
            () -> bookingRepository.findByIdForUpdate(bookingId).orElse(null));
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
        // skillars-deferred-91 AC5 Part A: stamp when the reservation was made so
        // PaymentPendingSweeper can age a stuck CAPTURE_PENDING row past
        // booking.payment_pending.capture_pending_max_hours.
        bp.setReservedAt(Instant.now());
        bp.setChargeIdempotencyKey(chargeKey);
        bookingPaymentRepository.save(bp);
        return CaptureReservation.RESERVED;
    }

    /** One reserved batch booking's part of a batch charge. */
    public record ChargeShare(UUID bookingId, BigDecimal credit, BigDecimal card) {
    }

    /**
     * fix/payment-charge-idempotency: writes a batch charge's idempotency key and each reserved row's real
     * credit / card share onto the CAPTURE_PENDING rows BEFORE the Stripe call, in its own committed
     * transaction — so a charge whose response is lost (or a JVM death right after it) leaves rows the
     * reconciler can settle from: which PaymentIntent to look for, and what each booking owes.
     *
     * <p>REQUIRES_NEW on the payment pool, like {@link #reserveCapture}: the listener calling this has only
     * READ these rows (they were inserted and committed by reserveCapture), so this second connection cannot
     * self-deadlock against it. Only rows still CAPTURE_PENDING are touched.
     */
    public void stampBatchCharge(List<ChargeShare> shares, String chargeKey) {
        RoutingDataSourceContext.set(DataSourceConfig.PAYMENT_REQUIRES_NEW_DATASOURCE_KEY);
        try {
            self.stampBatchChargeTransactional(shares, chargeKey);
        } finally {
            RoutingDataSourceContext.clear();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void stampBatchChargeTransactional(List<ChargeShare> shares, String chargeKey) {
        for (ChargeShare share : shares) {
            bookingPaymentRepository.findById(share.bookingId())
                .filter(bp -> BookingPaymentStatus.CAPTURE_PENDING.equals(bp.getStatus()))
                .ifPresent(bp -> {
                    bp.setCreditDebited(share.credit());
                    bp.setStripeCharged(share.card());
                    bp.setChargeIdempotencyKey(chargeKey);
                    bookingPaymentRepository.save(bp);
                });
        }
    }

    /**
     * fix/payment-charge-idempotency (M1): records a captured charge's PaymentIntent id on the still
     * CAPTURE_PENDING reservation(s) right after Stripe answered, in its own committed transaction. The
     * settle that follows can still fail and roll back (a booking-row lock it cannot get, a DB blip); the id
     * used to roll back with it, leaving a reservation that only a manual Stripe metadata search could
     * connect to the parent's money. Same REQUIRES_NEW / payment-pool reasoning as {@link #stampBatchCharge}.
     * Best-effort by contract: the caller logs and carries on (the row's charge key still finds the
     * PaymentIntent).
     */
    public void recordChargeIntent(Collection<UUID> bookingIds, String paymentIntentId) {
        RoutingDataSourceContext.set(DataSourceConfig.PAYMENT_REQUIRES_NEW_DATASOURCE_KEY);
        try {
            self.recordChargeIntentTransactional(bookingIds, paymentIntentId);
        } finally {
            RoutingDataSourceContext.clear();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordChargeIntentTransactional(Collection<UUID> bookingIds, String paymentIntentId) {
        bookingPaymentRepository.recordPendingPaymentIntent(bookingIds, paymentIntentId);
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
     * skillars-deferred-106 AC3.5: {@code platform.commission.rate} at this instant, stamped onto the
     * {@code booking_payments} row so the coach payout net (AC4.2) is computed from the rate that was
     * in force when the parent was charged — matching what the old destination charge's
     * {@code application_fee_amount} already locked at capture. A malformed config value must not
     * block a settle that has otherwise succeeded; leave the column NULL and let the payout code fall
     * back to the live rate (same as a pre-V133 row).
     */
    private BigDecimal currentCommissionRate() {
        try {
            return new BigDecimal(configService.getString("platform.commission.rate"));
        } catch (IllegalStateException | NumberFormatException e) {
            log.error("Could not stamp booking_payments.commission_rate — platform.commission.rate "
                + "unreadable ({}); leaving NULL, payout will fall back to the live rate", e.getMessage());
            return null;
        }
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
            // fix/payment-wallet-dispute-integrity: spend-only credit is consumed first.
            creditWalletService.debitForBooking(parentId, creditDebited, bookingId, "Session booking deduction");
        }
        BookingPayment bp = loadOrCreate(bookingId);
        bp.setCreditDebited(creditDebited);
        bp.setStripeCharged(stripeCharged);
        bp.setStripePaymentIntentId(paymentIntentId);
        bp.setBatchPaymentIntentId(batchPaymentIntentId);
        bp.setStatus(BookingPaymentStatus.CAPTURED);
        bp.setCapturedAt(Instant.now());
        // skillars-deferred-106 AC3.5: lock the commission rate at capture. The coach payout net is
        // computed from this stored rate, never from platform.commission.rate re-read at completion.
        bp.setCommissionRate(currentCommissionRate());
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
     * skillars-deferred-137 AC2: thin, non-transactional wrapper — see {@link #reserveCapture}'s own
     * Javadoc for why this split (and not a routing-key set inside the transactional method's body)
     * is required to route this REQUIRES_NEW acquisition to the dedicated payment pool.
     */
    public void persistPaymentFailure(UUID bookingId, BigDecimal creditToReverse,
                                       Long parentId, String parentEmail, String coachDisplayName,
                                       Instant requestedStartTime, String canonicalTimezone) {
        RoutingDataSourceContext.set(DataSourceConfig.PAYMENT_REQUIRES_NEW_DATASOURCE_KEY);
        try {
            self.persistPaymentFailureTransactional(bookingId, creditToReverse, parentId, parentEmail,
                coachDisplayName, requestedStartTime, canonicalTimezone);
        } finally {
            RoutingDataSourceContext.clear();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistPaymentFailureTransactional(UUID bookingId, BigDecimal creditToReverse,
                                       Long parentId, String parentEmail, String coachDisplayName,
                                       Instant requestedStartTime, String canonicalTimezone) {
        settleFailedCounter.increment();
        if (creditToReverse.compareTo(BigDecimal.ZERO) > 0) {
            creditWalletService.restoreBookingDeduction(parentId, creditToReverse, bookingId,
                "Payment failed - credit restored");
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
        // skillars-deferred-106 AC3.5: lock the commission rate at capture. The coach payout net is
        // computed from this stored rate, never from platform.commission.rate re-read at completion.
        bp.setCommissionRate(currentCommissionRate());
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
        // skillars-deferred-106 AC3.5: lock the commission rate at capture. The coach payout net is
        // computed from this stored rate, never from platform.commission.rate re-read at completion.
        bp.setCommissionRate(currentCommissionRate());
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
     *
     * <p>skillars-deferred-137 AC2: thin, non-transactional wrapper — see {@link #reserveCapture}'s own
     * Javadoc for why this split is required to route this REQUIRES_NEW acquisition to the dedicated
     * payment pool.
     */
    public void declineBatchBooking(UUID bookingId, UUID batchId) {
        RoutingDataSourceContext.set(DataSourceConfig.PAYMENT_REQUIRES_NEW_DATASOURCE_KEY);
        try {
            self.declineBatchBookingTransactional(bookingId, batchId);
        } finally {
            RoutingDataSourceContext.clear();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void declineBatchBookingTransactional(UUID bookingId, UUID batchId) {
        BookingPayment bp = loadOrCreate(bookingId);
        bp.setBatchPaymentIntentId(batchId);
        bp.setCreditDebited(BigDecimal.ZERO);
        bp.setStripeCharged(BigDecimal.ZERO);
        bp.setStatus(BookingPaymentStatus.CHARGE_FAILED);
        bookingPaymentRepository.save(bp);
        transitionOrReport(bookingId, BookingEvent.PAYMENT_FAILED);
    }

    /**
     * fix/payment-agreed-price-lock: a batch booking whose confirm failed AFTER the batch charge
     * succeeded. Unlike {@link #declineBatchBooking} (a zeroed CHARGE_FAILED row, "no money moved"),
     * the parent was charged {@code creditShare + stripeShare} for it, so this records those real
     * amounts under {@link BookingPaymentStatus#REFUNDED_TO_CREDIT}, declines the booking, and
     * enqueues the full share as a wallet {@code BOOKING_REFUND} on the durable outbox — all in one
     * {@code REQUIRES_NEW} transaction (the {@code MANDATORY} enqueue joins it), so the refund exists
     * exactly when the decline does. Idempotent per booking via {@code uq_pcl_booking_refund}. Same
     * REQUIRES_NEW / payment-pool routing as {@link #declineBatchBooking}, for the same reasons.
     */
    public void declineBatchBookingAfterCharge(UUID bookingId, UUID batchId, BigDecimal creditShare,
                                               BigDecimal stripeShare, String paymentIntentId, Long parentId) {
        declineBookingAfterCharge(bookingId, batchId, creditShare, stripeShare, paymentIntentId, parentId,
            "Batch booking could not be confirmed - payment returned as credit");
    }

    /**
     * fix/payment-charge-idempotency (M1): {@link #declineBatchBookingAfterCharge} for any booking whose
     * confirm failed after its charge succeeded — the single-booking settle and the charge reconciler use it
     * too (batchId null for a single booking). {@code creditShare} must be only credit that was actually
     * debited: since the deduction commits together with the confirm, a failed confirm debited none, and the
     * callers pass zero.
     */
    public void declineBookingAfterCharge(UUID bookingId, UUID batchId, BigDecimal creditShare,
                                          BigDecimal stripeShare, String paymentIntentId, Long parentId,
                                          String refundDescription) {
        RoutingDataSourceContext.set(DataSourceConfig.PAYMENT_REQUIRES_NEW_DATASOURCE_KEY);
        try {
            self.declineBatchBookingAfterChargeTransactional(bookingId, batchId, creditShare, stripeShare,
                paymentIntentId, parentId, refundDescription);
        } finally {
            RoutingDataSourceContext.clear();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void declineBatchBookingAfterChargeTransactional(UUID bookingId, UUID batchId, BigDecimal creditShare,
                                                            BigDecimal stripeShare, String paymentIntentId,
                                                            Long parentId, String refundDescription) {
        BookingPayment bp = loadOrCreate(bookingId);
        bp.setBatchPaymentIntentId(batchId);
        bp.setCreditDebited(creditShare);
        bp.setStripeCharged(stripeShare);
        bp.setStripePaymentIntentId(paymentIntentId);
        bp.setStatus(BookingPaymentStatus.REFUNDED_TO_CREDIT);
        bookingPaymentRepository.save(bp);
        transitionOrReport(bookingId, BookingEvent.PAYMENT_FAILED);
        BigDecimal refund = creditShare.add(stripeShare);
        if (refund.signum() > 0) {
            refundOutboxSupport.enqueueBookingRefund(parentId, refund, bookingId, refundDescription);
        }
    }

    /**
     * fix/payment-agreed-price-lock: credits a batch booking's already-charged share back to the wallet
     * when the booking can no longer be settled (settled elsewhere or gone after the batch charge). Its
     * own transaction so the {@code MANDATORY} outbox enqueue has one; touches no booking row.
     *
     * <p>fix/payment-dispute-credit-trace-and-lost-dispute: the booking's {@code booking_payments} row
     * belongs to whatever settled it, so the credit records the batch charge itself and its card part
     * ({@code paymentIntentId} / {@code cardShare}) — a later card dispute on the batch charge finds it.
     */
    public void refundBatchShareToCredit(Long parentId, UUID bookingId, UUID batchId, BigDecimal amount,
                                         String paymentIntentId, BigDecimal cardShare) {
        RoutingDataSourceContext.set(DataSourceConfig.PAYMENT_REQUIRES_NEW_DATASOURCE_KEY);
        try {
            self.refundBatchShareToCreditTransactional(parentId, bookingId, batchId, amount, paymentIntentId,
                cardShare);
        } finally {
            RoutingDataSourceContext.clear();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refundBatchShareToCreditTransactional(Long parentId, UUID bookingId, UUID batchId, BigDecimal amount,
                                                      String paymentIntentId, BigDecimal cardShare) {
        boolean card = paymentIntentId != null && cardShare != null && cardShare.signum() > 0;
        // fix/payment-wallet-dispute-integrity: its own ledger kind (BATCH_SHARE_REFUND), so it never takes the
        // booking's one BOOKING_REFUND slot from a later real cancellation refund.
        refundOutboxSupport.enqueueBatchShareRefund(parentId, amount, bookingId,
            "Batch booking could not be confirmed - payment returned as credit (batch " + batchId + ")",
            card ? paymentIntentId : null, card ? cardShare : null);
    }
}
