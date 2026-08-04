package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.BatchBookingAcceptedEvent;
import com.softropic.skillars.platform.booking.contract.BookingAcceptedEvent;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachPricingRepository;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
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
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentLifecycleService {

    private final CreditWalletService creditWalletService;
    private final PackSessionService packSessionService;
    private final PaymentGateway paymentGateway;
    private final BookingPaymentRepository bookingPaymentRepository;
    private final BookingRepository bookingRepository;
    private final CoachPricingRepository coachPricingRepository;
    private final BookingPaymentPersistenceService persistenceService;
    private final PlatformTransactionManager transactionManager;

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

    // ─── Single booking ───────────────────────────────────────────────────────

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingAccepted(BookingAcceptedEvent event) {
        if (bookingPaymentRepository.existsById(event.getBookingId())) {
            log.warn("Duplicate BookingAcceptedEvent ignored: bookingId={}", event.getBookingId());
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
        } catch (PaymentGatewayException e) {
            log.error("Pack session deduction failed: bookingId={} purchaseId={}", bookingId, purchaseId);
            persistenceService.persistPaymentFailure(bookingId, BigDecimal.ZERO,
                parentId, parentEmail, coachDisplayName, requestedStartTime, canonicalTimezone);
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
            if (bookingPaymentRepository.existsById(bookingId)) continue;
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

        // Compute credit subtotal from individual booking prices (NOT event.getTotalAmount())
        BigDecimal creditSubtotal = BigDecimal.ZERO;
        for (UUID bookingId : creditIds) {
            Booking b = bookingRepository.findById(bookingId).orElse(null);
            if (b != null) {
                BigDecimal price = coachPricingRepository.findByCoachId(b.getCoachId())
                    .map(p -> p.getPerSessionPrice())
                    .orElse(BigDecimal.ZERO);
                creditSubtotal = creditSubtotal.add(price);
            }
        }

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
                for (UUID bookingId : creditIds) {
                    if (!bookingPaymentRepository.existsById(bookingId)) {
                        persistenceService.declineBatchBooking(bookingId, event.getBatchId());
                    }
                }
                return;
            }
        }

        String finalPaymentIntentId = paymentIntentId;
        BigDecimal remainingCredit = creditToUse;
        for (UUID bookingId : creditIds) {
            if (bookingPaymentRepository.existsById(bookingId)) continue;
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
