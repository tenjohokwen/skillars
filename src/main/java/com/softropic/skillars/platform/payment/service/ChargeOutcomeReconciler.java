package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
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
import com.softropic.skillars.platform.payment.contract.BookingPaymentStatus;
import com.softropic.skillars.platform.payment.contract.ChargeLookup;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.repo.BookingPayment;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * fix/payment-charge-idempotency (H2): resolves CAPTURE_PENDING reservations whose Stripe charge outcome is
 * unknown — the charge call timed out / lost its connection / got a 5xx or an idempotency conflict
 * ({@code ChargeOutcomeUnknownException}), or the JVM died between the call and the settle, or the settle and
 * its compensation both failed after a successful charge.
 *
 * <p>Before this, an unknown outcome was declined on the spot: the booking DECLINED with a terminal
 * CHARGE_FAILED row while the parent's card may have been charged, with no PaymentIntent id stored anywhere.
 * Now the reservation carries the charge's idempotency key (written before the call, also on the
 * PaymentIntent's {@code chargeKey} metadata), and this job asks Stripe what happened:
 * <ul>
 *   <li><strong>succeeded</strong> — settle the booking exactly as the listener would have (credit share
 *       debited under the wallet lock + CAPTURED + CONFIRMED). If it can no longer be settled (wallet no
 *       longer covers the credit share, or the settle fails) it is declined and its card share credited to
 *       the wallet — never kept.</li>
 *   <li><strong>processing</strong> — not decided yet; looked at again next run.</li>
 *   <li><strong>any other status, or no PaymentIntent at all</strong> — nothing was captured: declined with a
 *       zeroed CHARGE_FAILED row and the parent told, as a synchronous decline would have.</li>
 *   <li><strong>Stripe unreachable / amounts disagree</strong> — left alone (the latter at ERROR for an
 *       operator); {@code PaymentPendingSweeper} still abandons a reservation stuck past
 *       {@code capture_pending_max_hours}.</li>
 * </ul>
 *
 * <p>Only reservations older than {@code booking.payment.charge_reconcile_after_minutes} are looked at:
 * long enough that the listener that made the call is done with it (the SDK's connect / read timeouts and
 * network retries are bounded in {@code PaymentConfig}), so this never races an in-flight charge.
 *
 * <p>Concurrency: ShedLock keeps one run cluster-wide. Each booking is resolved in its own transaction that
 * takes the payer's wallet lock (when there is credit to debit) and then the booking-row lock — the order the
 * settle listener takes them — and re-reads the reservation under them, so a settle or sweep that got there
 * first is observed, not overwritten. Compensation runs after that transaction ends: it is REQUIRES_NEW on
 * the same booking row and would self-deadlock inside it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChargeOutcomeReconciler {

    static final String RECONCILE_AFTER_MINUTES_KEY = "booking.payment.charge_reconcile_after_minutes";
    private static final long RECONCILE_AFTER_DEFAULT_MINUTES = 10L;
    private static final long RECONCILE_AFTER_MIN_MINUTES = 5L;
    private static final long RECONCILE_AFTER_MAX_MINUTES = 1440L;

    private static final int PAGE_SIZE = 100;
    private static final int MAX_ROWS_PER_RUN = 1000;
    /** PaymentIntents are searched from a little before the reservation, for clock skew. */
    private static final Duration LOOKUP_SLACK = Duration.ofMinutes(10);

    private static final String RECONCILED_COUNTER = "booking.payment.charge_reconciled";
    private static final String REFUND_DESCRIPTION = "Booking could not be confirmed - payment returned as credit";

    private final BookingPaymentRepository bookingPaymentRepository;
    private final BookingRepository bookingRepository;
    private final BookingService bookingService;
    private final BookingPaymentPersistenceService persistenceService;
    private final CreditWalletService creditWalletService;
    private final PaymentGateway paymentGateway;
    private final ConfigService configService;
    private final CoachProfileRepository coachProfileRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;
    private final PessimisticLockRetryer lockRetryer;

    enum Outcome { SETTLED, DECLINED, COMPENSATE, PENDING, SKIPPED }

    // lockAtMostFor is 2x the fixedDelay, the sibling schedulers' margin (see PaymentPendingSweeper).
    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
    @SchedulerLock(name = "ChargeOutcomeReconciler_reconcile", lockAtMostFor = "PT20M", lockAtLeastFor = "PT1M")
    public void reconcile() {
        long afterMinutes = configService.getBoundedLong(RECONCILE_AFTER_MINUTES_KEY,
            RECONCILE_AFTER_DEFAULT_MINUTES, RECONCILE_AFTER_MIN_MINUTES, RECONCILE_AFTER_MAX_MINUTES);
        Instant reservedBefore = Instant.now().minus(Duration.ofMinutes(afterMinutes));

        // One Stripe lookup per charge key per run: the rows of one batch share a PaymentIntent.
        Map<String, Optional<ChargeLookup>> lookups = new HashMap<>();
        Instant cursorAt = Instant.EPOCH;
        UUID cursorId = new UUID(0L, 0L);
        int seen = 0;
        while (seen < MAX_ROWS_PER_RUN) {
            final Instant pageAfterAt = cursorAt;
            final UUID pageAfterId = cursorId;
            List<BookingPayment> page = transactionTemplate.execute(s -> bookingPaymentRepository
                .findReconcilableCharges(reservedBefore, pageAfterAt, pageAfterId, PageRequest.of(0, PAGE_SIZE)));
            if (page == null || page.isEmpty()) break;
            for (BookingPayment bp : page) {
                seen++;
                cursorAt = bp.getReservedAt();
                cursorId = bp.getBookingId();
                try {
                    reconcileOne(bp, lookups);
                } catch (RuntimeException e) {
                    // Stripe unreachable, a lock not available, ... — the row is still CAPTURE_PENDING and the
                    // next run tries again.
                    log.warn("ChargeOutcomeReconciler: booking {} not resolved this run: {}", bp.getBookingId(),
                        e.toString());
                }
            }
            if (page.size() < PAGE_SIZE) break;
        }
        if (seen > 0) {
            log.info("ChargeOutcomeReconciler: looked at {} reservation(s) with an unknown charge outcome", seen);
        }
    }

    Outcome reconcileOne(BookingPayment bp, Map<String, Optional<ChargeLookup>> lookups) {
        UUID bookingId = bp.getBookingId();
        Booking booking = transactionTemplate.execute(s -> bookingRepository.findById(bookingId).orElse(null));
        if (booking == null) {
            log.error("[CHARGE_RECONCILE_NO_BOOKING] bookingId={} key={} — reservation without a booking; reconcile "
                + "by hand", bookingId, bp.getChargeIdempotencyKey());
            return count(Outcome.SKIPPED);
        }
        Long parentId = booking.getParentId();

        Optional<ChargeLookup> lookup = bp.getStripePaymentIntentId() != null
            ? Optional.of(paymentGateway.retrieveCharge(bp.getStripePaymentIntentId()))
            : lookups.computeIfAbsent(bp.getChargeIdempotencyKey(), key -> paymentGateway
                .findChargeByIdempotencyKey(parentId, key, bp.getReservedAt().minus(LOOKUP_SLACK)));

        if (lookup.isPresent() && lookup.get().pending()) {
            log.info("Charge for booking {} still {} at Stripe — looking again next run", bookingId,
                lookup.get().status());
            return count(Outcome.PENDING);
        }
        if (lookup.isEmpty() || !lookup.get().succeeded()) {
            // No PaymentIntent (the request never reached Stripe) or one that captured nothing.
            Outcome outcome = transactionTemplate.execute(s -> declineUncharged(bookingId, parentId,
                lookup.map(ChargeLookup::status).orElse("none")));
            return count(outcome);
        }

        ChargeLookup charge = lookup.get();
        if (!amountMatches(bp, charge)) {
            return count(Outcome.SKIPPED);
        }
        Outcome outcome;
        try {
            outcome = transactionTemplate.execute(s -> settle(bookingId, parentId, charge.paymentIntentId(),
                bp.getCreditDebited()));
        } catch (RuntimeException e) {
            log.error("[CHARGE_RECONCILE_SETTLE_FAILED] bookingId={} intentId={} — declining and returning the card "
                + "share as credit", bookingId, charge.paymentIntentId(), e);
            outcome = Outcome.COMPENSATE;
        }
        if (outcome == Outcome.COMPENSATE) {
            // Outside the settle transaction (REQUIRES_NEW on the same booking row). No credit was debited.
            persistenceService.declineBookingAfterCharge(bookingId, bp.getBatchPaymentIntentId(), BigDecimal.ZERO,
                bp.getStripeCharged(), charge.paymentIntentId(), parentId, REFUND_DESCRIPTION);
            log.warn("[CHARGE_RECONCILE_COMPENSATED] bookingId={} intentId={} card={} — declined, card share "
                + "returned as wallet credit", bookingId, charge.paymentIntentId(), bp.getStripeCharged());
        }
        return count(outcome);
    }

    /**
     * The PaymentIntent must be for exactly what the reservation(s) under its key were to be charged. A
     * difference means this is not the charge we think it is — an operator decides.
     */
    private boolean amountMatches(BookingPayment bp, ChargeLookup charge) {
        BigDecimal expected = transactionTemplate.execute(s -> bookingPaymentRepository
            .findByChargeIdempotencyKey(bp.getChargeIdempotencyKey()).stream()
            .map(BookingPayment::getStripeCharged)
            .reduce(BigDecimal.ZERO, BigDecimal::add));
        if (charge.amount() != null && expected != null && charge.amount().compareTo(expected) == 0) {
            return true;
        }
        Counter.builder(RECONCILED_COUNTER).tag("outcome", "amount_mismatch").register(meterRegistry).increment();
        log.error("[CHARGE_RECONCILE_AMOUNT_MISMATCH] bookingId={} key={} intentId={} charged={} expected={} — left "
                + "CAPTURE_PENDING; reconcile by hand", bp.getBookingId(), bp.getChargeIdempotencyKey(),
            charge.paymentIntentId(), charge.amount(), expected);
        return false;
    }

    /** Runs inside a transaction. Wallet lock first, then the booking row — the settle listener's order. */
    private Outcome settle(UUID bookingId, Long parentId, String paymentIntentId, BigDecimal creditHint) {
        // The wallet lock is taken only when there is credit to debit; the shares were fixed (stamped) before
        // the charge, so the page's snapshot is good enough to decide that. The reservation itself is read
        // for the first time in this transaction only AFTER the booking lock — a read before it would be
        // served stale from the persistence context afterwards.
        BigDecimal balance = creditHint != null && creditHint.signum() > 0
            ? creditWalletService.lockAndGetBalance(parentId) : null;
        Booking booking = lockBooking(bookingId);
        // Read under the booking lock: a sweep that abandoned it, or a late listener, wins.
        BookingPayment bp = bookingPaymentRepository.findById(bookingId).orElse(null);
        if (booking == null || bp == null || !BookingPaymentStatus.CAPTURE_PENDING.equals(bp.getStatus())) {
            return Outcome.SKIPPED;
        }
        BigDecimal credit = bp.getCreditDebited() == null ? BigDecimal.ZERO : bp.getCreditDebited();
        if (credit.signum() > 0 && balance == null) {
            balance = creditWalletService.lockAndGetBalance(parentId);
        }
        if (!"PAYMENT_PENDING".equals(booking.getStatus())) {
            log.error("[CHARGE_RECONCILE_BOOKING_MOVED] bookingId={} status={} intentId={} — charged, but the booking "
                + "is no longer PAYMENT_PENDING; reconcile by hand", bookingId, booking.getStatus(), paymentIntentId);
            return Outcome.SKIPPED;
        }
        if (balance != null && balance.compareTo(credit) < 0) {
            log.warn("Wallet of payer {} no longer covers booking {}'s credit share ({} < {}) — compensating",
                parentId, bookingId, balance, credit);
            return Outcome.COMPENSATE;
        }
        CoachProfile coach = coachProfileRepository.findById(booking.getCoachId()).orElse(null);
        // REQUIRED: joins this transaction — BOOKING_DEDUCTION (reference = bookingId) + CAPTURED + CONFIRMED.
        persistenceService.persistPaymentSuccess(bookingId, credit, bp.getStripeCharged(), paymentIntentId,
            bp.getBatchPaymentIntentId(), parentId, resolveEmail(parentId, bookingId),
            coach != null ? coach.getDisplayName() : "Coach",
            booking.getRequestedStartTime(), booking.getCanonicalTimezone());
        log.warn("[CHARGE_RECONCILE_SETTLED] bookingId={} intentId={} credit={} card={}", bookingId,
            paymentIntentId, credit, bp.getStripeCharged());
        return Outcome.SETTLED;
    }

    /** Runs inside a transaction. Same triple as BookingPaymentPersistenceService.persistPaymentFailure. */
    private Outcome declineUncharged(UUID bookingId, Long parentId, String stripeStatus) {
        Booking booking = lockBooking(bookingId);
        BookingPayment bp = bookingPaymentRepository.findById(bookingId).orElse(null);
        if (booking == null || bp == null || !BookingPaymentStatus.CAPTURE_PENDING.equals(bp.getStatus())) {
            return Outcome.SKIPPED;
        }
        if (!"PAYMENT_PENDING".equals(booking.getStatus())) {
            log.error("[CHARGE_RECONCILE_BOOKING_MOVED] bookingId={} status={} — uncharged reservation on a booking "
                + "no longer PAYMENT_PENDING; reconcile by hand", bookingId, booking.getStatus());
            return Outcome.SKIPPED;
        }
        bp.setCreditDebited(BigDecimal.ZERO);
        bp.setStripeCharged(BigDecimal.ZERO);
        bp.setStatus(BookingPaymentStatus.CHARGE_FAILED);
        bookingPaymentRepository.save(bp);
        bookingService.transition(bookingId, BookingEvent.PAYMENT_FAILED, new TransitionContext(ActorRole.SYSTEM, null));
        CoachProfile coach = coachProfileRepository.findById(booking.getCoachId()).orElse(null);
        eventPublisher.publishEvent(new BookingDeclinedEvent(this, bookingId, parentId,
            resolveEmail(parentId, bookingId), coach != null ? coach.getDisplayName() : "Coach",
            booking.getRequestedStartTime(), booking.getCanonicalTimezone()));
        log.warn("[CHARGE_RECONCILE_DECLINED] bookingId={} key={} stripeStatus={} — nothing was captured; declined",
            bookingId, bp.getChargeIdempotencyKey(), stripeStatus);
        return Outcome.DECLINED;
    }

    private Booking lockBooking(UUID bookingId) {
        return lockRetryer.withBoundedRetry("ChargeOutcomeReconciler.lockBooking",
            () -> bookingRepository.findByIdForUpdate(bookingId).orElse(null));
    }

    private Outcome count(Outcome outcome) {
        Counter.builder(RECONCILED_COUNTER).tag("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT))
            .register(meterRegistry).increment();
        return outcome;
    }

    private String resolveEmail(Long userId, UUID bookingId) {
        return userRepository.findById(userId).map(u -> u.getEmail()).orElseGet(() -> {
            log.warn("Could not resolve email for userId={} bookingId={} — notification will be skipped",
                userId, bookingId);
            return "";
        });
    }
}
