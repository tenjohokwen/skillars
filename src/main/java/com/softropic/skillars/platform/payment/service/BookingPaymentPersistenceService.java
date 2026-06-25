package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.ActorRole;
import com.softropic.skillars.platform.booking.contract.BookingConfirmedEvent;
import com.softropic.skillars.platform.booking.contract.BookingDeclinedEvent;
import com.softropic.skillars.platform.booking.contract.BookingEvent;
import com.softropic.skillars.platform.booking.contract.TransitionContext;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.platform.payment.repo.BookingPayment;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingPaymentPersistenceService {

    private final CreditWalletService creditWalletService;
    private final BookingPaymentRepository bookingPaymentRepository;
    private final BookingService bookingService;
    private final ApplicationEventPublisher eventPublisher;

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
        if (creditDebited.compareTo(BigDecimal.ZERO) > 0) {
            creditWalletService.writeLedgerEntry(parentId, creditDebited.negate(),
                "BOOKING_DEDUCTION", bookingId, "Session booking deduction");
        }
        BookingPayment bp = new BookingPayment();
        bp.setBookingId(bookingId);
        bp.setCreditDebited(creditDebited);
        bp.setStripeCharged(stripeCharged);
        bp.setStripePaymentIntentId(paymentIntentId);
        bp.setBatchPaymentIntentId(batchPaymentIntentId);
        bp.setStatus("CAPTURED");
        bp.setCapturedAt(Instant.now());
        bookingPaymentRepository.save(bp);
        bookingService.transition(bookingId, BookingEvent.PAYMENT_CAPTURED,
            new TransitionContext(ActorRole.SYSTEM, null));
        eventPublisher.publishEvent(new BookingConfirmedEvent(
            this, bookingId, parentId, parentEmail, coachDisplayName, requestedStartTime, canonicalTimezone));
    }

    @Transactional
    public void persistPaymentFailure(UUID bookingId, BigDecimal creditToReverse,
                                       Long parentId, String parentEmail, String coachDisplayName,
                                       Instant requestedStartTime, String canonicalTimezone) {
        if (creditToReverse.compareTo(BigDecimal.ZERO) > 0) {
            creditWalletService.writeLedgerEntry(parentId, creditToReverse,
                "BOOKING_DEDUCTION_REVERSAL", bookingId, "Payment failed - credit restored");
        }
        BookingPayment bp = new BookingPayment();
        bp.setBookingId(bookingId);
        bp.setCreditDebited(BigDecimal.ZERO);
        bp.setStripeCharged(BigDecimal.ZERO);
        bp.setStatus("CHARGE_FAILED");
        bookingPaymentRepository.save(bp);
        bookingService.transition(bookingId, BookingEvent.PAYMENT_FAILED,
            new TransitionContext(ActorRole.SYSTEM, null));
        eventPublisher.publishEvent(new BookingDeclinedEvent(
            this, bookingId, parentId, parentEmail, coachDisplayName, requestedStartTime, canonicalTimezone));
    }

    @Transactional
    public void confirmPackBatchPayment(UUID bookingId, UUID batchId, Long parentId, String parentEmail,
                                         String coachDisplayName, Instant requestedStartTime,
                                         String canonicalTimezone) {
        BookingPayment bp = new BookingPayment();
        bp.setBookingId(bookingId);
        bp.setBatchPaymentIntentId(batchId);
        bp.setCreditDebited(BigDecimal.ZERO);
        bp.setStripeCharged(BigDecimal.ZERO);
        bp.setStatus("CAPTURED");
        bp.setCapturedAt(Instant.now());
        bookingPaymentRepository.save(bp);
        bookingService.transition(bookingId, BookingEvent.PAYMENT_CAPTURED,
            new TransitionContext(ActorRole.SYSTEM, null));
        eventPublisher.publishEvent(new BookingConfirmedEvent(
            this, bookingId, parentId, parentEmail, coachDisplayName, requestedStartTime, canonicalTimezone));
    }

    @Transactional
    public void confirmCreditBatchPayment(UUID bookingId, UUID batchId, BigDecimal creditDebited,
                                           BigDecimal stripeCharged, String paymentIntentId,
                                           Long parentId, String parentEmail, String coachDisplayName,
                                           Instant requestedStartTime, String canonicalTimezone) {
        BookingPayment bp = new BookingPayment();
        bp.setBookingId(bookingId);
        bp.setBatchPaymentIntentId(batchId);
        bp.setCreditDebited(creditDebited);
        bp.setStripeCharged(stripeCharged);
        bp.setStripePaymentIntentId(paymentIntentId);
        bp.setStatus("CAPTURED");
        bp.setCapturedAt(Instant.now());
        bookingPaymentRepository.save(bp);
        bookingService.transition(bookingId, BookingEvent.PAYMENT_CAPTURED,
            new TransitionContext(ActorRole.SYSTEM, null));
        eventPublisher.publishEvent(new BookingConfirmedEvent(
            this, bookingId, parentId, parentEmail, coachDisplayName, requestedStartTime, canonicalTimezone));
    }

    @Transactional
    public void declineBatchBooking(UUID bookingId, UUID batchId) {
        BookingPayment bp = new BookingPayment();
        bp.setBookingId(bookingId);
        bp.setBatchPaymentIntentId(batchId);
        bp.setCreditDebited(BigDecimal.ZERO);
        bp.setStripeCharged(BigDecimal.ZERO);
        bp.setStatus("CHARGE_FAILED");
        bookingPaymentRepository.save(bp);
        bookingService.transition(bookingId, BookingEvent.PAYMENT_FAILED,
            new TransitionContext(ActorRole.SYSTEM, null));
    }
}
