package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.ActorRole;
import com.softropic.skillars.platform.booking.contract.BookingDeclinedEvent;
import com.softropic.skillars.platform.booking.contract.BookingEvent;
import com.softropic.skillars.platform.booking.contract.TransitionContext;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.repo.BookingPayment;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Recovers bookings stranded in PAYMENT_PENDING — but only the ones that provably moved no money.
 *
 * <p>The stranding window is real and nothing else closes it. {@code BookingService
 * .acceptAndInitiatePayment} commits the booking into PAYMENT_PENDING and settlement happens in
 * {@code PaymentLifecycleService}'s AFTER_COMMIT listeners, which have no retry and no dead-letter
 * queue. If the JVM dies in between, or the listener's own transaction fails outright, the booking
 * rests there forever: PAYMENT_PENDING is in {@code ACTIVE_SLOT_STATUSES} and in V87's exclusion
 * constraint, so the row holds the coach's slot against every other booking, and the only exit the
 * state machine offers outside the payment listener is the parent's own CANCEL_PARENT.
 *
 * <p><strong>Credit-funded stranded bookings are knowingly left alone. Do not "complete" this
 * sweeper by dropping the pack-funded precondition.</strong> On the credit path
 * {@code chargeAndCapture} / {@code chargeAndCaptureForBatch} run BEFORE the DB writes that record
 * them, so a crash in between leaves money captured at Stripe with no {@code booking_payments} row,
 * no ledger entry and no other durable trace — the gateway exposes no capture lookup, no
 * {@code payment_intent.*}/{@code charge.*} webhook is handled, and the gateway's only durable
 * write ({@code stripeCustomer.lastPaymentIntentId}) is transactional and per-parent. There is no
 * signal this class could read to tell "never charged" from "charged and lost the record", so
 * declining such a booking would hand the coach's slot back at the cost of charging a parent for
 * nothing. Pack-funded settlement never calls Stripe at all, which is what makes it decidable.
 *
 * <p>The change that would make full coverage safe — a durable pre-capture record, plus making the
 * four {@code existsById} idempotency checks in PaymentLifecycleService status-aware — is tracked
 * in {@code deferred-work.md} under the skillars-deferred-15 audit block.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentPendingSweeper {

    static final String GRACE_MINUTES_KEY = "booking.payment_pending_sweep_grace_minutes";
    private static final long GRACE_DEFAULT_MINUTES = 120L;
    private static final long GRACE_MIN_MINUTES = 15L;
    private static final long GRACE_MAX_MINUTES = 10080L; // 7 days

    private static final String SWEPT_COUNTER = "booking.payment_pending.swept";
    private static final String UNRECOVERABLE_COUNTER = "booking.payment_pending.unrecoverable";

    private final BookingRepository bookingRepository;
    private final BookingPaymentRepository bookingPaymentRepository;
    private final BookingService bookingService;
    private final ConfigService configService;
    private final CoachProfileRepository coachProfileRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    // lockAtMostFor is deliberately 2x the fixedDelay, not equal to it: with both at 15 minutes a run
    // that merely reached its own next tick would have its lock expire mid-execution and a second
    // instance could start an overlapping sweep. The sibling schedulers keep the same kind of margin
    // (SessionPackForfeitureScheduler 60m/15m, BookingExpiryScheduler 5m/15m). Overlap would not
    // double-decline — sweepOne re-reads and re-asserts PAYMENT_PENDING inside its own transaction —
    // but it would double-log and waste work.
    @Scheduled(fixedDelay = 15, timeUnit = TimeUnit.MINUTES)
    @SchedulerLock(name = "PaymentPendingSweeper_sweep", lockAtMostFor = "PT30M", lockAtLeastFor = "PT2M")
    public void sweepStrandedPayments() {
        long graceMinutes = configService.getBoundedLong(
            GRACE_MINUTES_KEY, GRACE_DEFAULT_MINUTES, GRACE_MIN_MINUTES, GRACE_MAX_MINUTES);
        Instant threshold = Instant.now().minus(Duration.ofMinutes(graceMinutes));

        List<Booking> stranded = transactionTemplate.execute(
            status -> bookingRepository.findPaymentPendingOlderThan(threshold));
        if (stranded == null || stranded.isEmpty()) return;

        log.info("PaymentPendingSweeper: {} booking(s) in PAYMENT_PENDING older than {} minutes",
            stranded.size(), graceMinutes);

        for (Booking candidate : stranded) {
            // One transaction per booking, as in SessionPackForfeitureScheduler: a single long
            // transaction over the whole batch lets one failure discard every sibling's write.
            try {
                transactionTemplate.executeWithoutResult(status -> sweepOne(candidate.getId()));
            } catch (OptimisticLockingFailureException e) {
                // Benign and self-correcting: the booking settled between this transaction's re-read
                // and its write, so the settle won and there is nothing stranded. Logging it at ERROR
                // alongside the genuinely unrecoverable cases would dilute the signal this class
                // exists to produce — an ERROR here must mean "an operator has to reconcile this".
                log.info("Booking {} settled concurrently during the sweep — skipped", candidate.getId());
            } catch (Exception e) {
                log.error("PaymentPendingSweeper failed on booking {}", candidate.getId(), e);
            }
        }
    }

    private void sweepOne(UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null) return;
        // The row may have settled between the select above and this transaction opening.
        if (!"PAYMENT_PENDING".equals(booking.getStatus())) return;

        if (booking.getSessionPackPurchaseId() == null) {
            reportUnrecoverable(booking, "CREDIT_FUNDED");
            return;
        }
        if (bookingPaymentRepository.existsById(bookingId)) {
            // Every writer of booking_payments writes the row and the status transition in one
            // transaction, so this pairing is a data-integrity failure, not a stranded booking.
            reportUnrecoverable(booking, "PAYMENT_ROW_PRESENT");
            return;
        }

        // Same triple BookingPaymentPersistenceService.persistPaymentFailure performs, in the same
        // order: payment row, then the transition, then the event.
        BookingPayment bp = new BookingPayment();
        bp.setBookingId(bookingId);
        bp.setCreditDebited(BigDecimal.ZERO);
        bp.setStripeCharged(BigDecimal.ZERO);
        bp.setStatus("CHARGE_FAILED");
        bookingPaymentRepository.save(bp);

        bookingService.transition(bookingId, BookingEvent.PAYMENT_FAILED,
            new TransitionContext(ActorRole.SYSTEM, null));

        CoachProfile coach = coachProfileRepository.findById(booking.getCoachId()).orElse(null);
        eventPublisher.publishEvent(new BookingDeclinedEvent(
            this, bookingId, booking.getParentId(), resolveEmail(booking.getParentId(), bookingId),
            coach != null ? coach.getDisplayName() : "Coach",
            booking.getRequestedStartTime(), booking.getCanonicalTimezone()));

        Counter.builder(SWEPT_COUNTER).register(meterRegistry).increment();
        log.warn("Swept stranded pack-funded booking to DECLINED: bookingId={} batchId={} parentId={} "
                + "sessionPackPurchaseId={} updatedAt={}",
            bookingId, booking.getBatchId(), booking.getParentId(),
            booking.getSessionPackPurchaseId(), booking.getUpdatedAt());
    }

    /**
     * ERROR, not WARN: these are the bookings an operator has to reconcile against Stripe by hand.
     * The counter is tagged so the credit-funded case — the one where money may already have left
     * the parent — is alertable on its own.
     */
    private void reportUnrecoverable(Booking booking, String reason) {
        Counter.builder(UNRECOVERABLE_COUNTER).tag("reason", reason).register(meterRegistry).increment();
        log.error("Stranded PAYMENT_PENDING booking cannot be swept automatically ({}): bookingId={} "
                + "batchId={} parentId={} sessionPackPurchaseId={} updatedAt={}",
            reason, booking.getId(), booking.getBatchId(), booking.getParentId(),
            booking.getSessionPackPurchaseId(), booking.getUpdatedAt());
    }

    private String resolveEmail(Long userId, UUID bookingId) {
        return userRepository.findById(userId).map(u -> u.getEmail()).orElseGet(() -> {
            log.warn("Could not resolve email for userId={} bookingId={} — decline notification will be skipped",
                userId, bookingId);
            return "";
        });
    }
}
