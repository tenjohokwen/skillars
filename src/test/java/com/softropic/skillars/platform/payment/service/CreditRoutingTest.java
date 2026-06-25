package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.BookingAcceptedEvent;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachPricingRepository;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreditRoutingTest {

    @Mock CreditWalletService creditWalletService;
    @Mock PackSessionService packSessionService;
    @Mock PaymentGateway paymentGateway;
    @Mock BookingPaymentRepository bookingPaymentRepository;
    @Mock BookingRepository bookingRepository;
    @Mock CoachPricingRepository coachPricingRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    // P2: BookingPaymentPersistenceService holds all @Transactional write logic extracted
    // from PaymentLifecycleService to fix Spring AOP proxy bypass (Group 2 adversarial patch).
    // Without this mock, PaymentLifecycleService.persistenceService is null → NPE on every path.
    @Mock BookingPaymentPersistenceService persistenceService;

    @InjectMocks PaymentLifecycleService service;

    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final UUID COACH_ID = UUID.randomUUID();
    private static final Long PARENT_ID = 1001L;
    private static final BigDecimal SESSION_PRICE = new BigDecimal("50.00");

    private BookingAcceptedEvent event(UUID packPurchaseId) {
        return new BookingAcceptedEvent(this, BOOKING_ID, PARENT_ID, COACH_ID,
            SESSION_PRICE, packPurchaseId, "parent@test.com", "Coach Name",
            Instant.now(), "Europe/Berlin");
    }

    @BeforeEach
    void setUp() {
        when(bookingPaymentRepository.existsById(BOOKING_ID)).thenReturn(false);
    }

    @Test
    void caseA_fullCreditCoversBooking_noStripeCall() {
        when(creditWalletService.getBalance(PARENT_ID)).thenReturn(new BigDecimal("100.00"));

        service.onBookingAccepted(event(null));

        verify(paymentGateway, never()).chargeAndCapture(any(), any(), any(), any());
        // creditToUse = min(100, 50) = 50; stripeAmount = 0
        verify(persistenceService).persistPaymentSuccess(
            eq(BOOKING_ID), eq(new BigDecimal("50.00")), eq(BigDecimal.ZERO),
            isNull(), isNull(),
            eq(PARENT_ID), anyString(), anyString(), any(Instant.class), anyString());
    }

    @Test
    void caseB_partialCredit_stripeChargedForDeficit() {
        when(creditWalletService.getBalance(PARENT_ID)).thenReturn(new BigDecimal("20.00"));
        when(paymentGateway.chargeAndCapture(BOOKING_ID, PARENT_ID, COACH_ID, new BigDecimal("30.00")))
            .thenReturn("pi_test_123");

        service.onBookingAccepted(event(null));

        // creditToUse = min(20, 50) = 20; stripeAmount = 30
        verify(paymentGateway).chargeAndCapture(BOOKING_ID, PARENT_ID, COACH_ID, new BigDecimal("30.00"));
        // P3: assert status=CAPTURED is delegated via persistPaymentSuccess (AC 3 requirement)
        verify(persistenceService).persistPaymentSuccess(
            eq(BOOKING_ID), eq(new BigDecimal("20.00")), eq(new BigDecimal("30.00")),
            eq("pi_test_123"), isNull(),
            eq(PARENT_ID), anyString(), anyString(), any(Instant.class), anyString());
    }

    @Test
    void caseC_zeroCredit_fullStripeCharge() {
        when(creditWalletService.getBalance(PARENT_ID)).thenReturn(BigDecimal.ZERO);
        when(paymentGateway.chargeAndCapture(BOOKING_ID, PARENT_ID, COACH_ID, SESSION_PRICE))
            .thenReturn("pi_full_123");

        service.onBookingAccepted(event(null));

        // creditToUse = 0; stripeAmount = 50
        verify(paymentGateway).chargeAndCapture(BOOKING_ID, PARENT_ID, COACH_ID, SESSION_PRICE);
        // P4: assert status=CAPTURED delegated via persistPaymentSuccess (AC 3 requirement)
        verify(persistenceService).persistPaymentSuccess(
            eq(BOOKING_ID), eq(BigDecimal.ZERO), eq(new BigDecimal("50.00")),
            eq("pi_full_123"), isNull(),
            eq(PARENT_ID), anyString(), anyString(), any(Instant.class), anyString());
    }

    @Test
    void packBasedBooking_noStripeNoCreditConsulted() {
        UUID packId = UUID.randomUUID();

        service.onBookingAccepted(event(packId));

        verify(creditWalletService, never()).getBalance(any());
        verify(paymentGateway, never()).chargeAndCapture(any(), any(), any(), any());
        verify(packSessionService).deductSession(packId);
        // P5: assert BookingPayment with status=CAPTURED is persisted (AC 4 requirement)
        verify(persistenceService).persistPaymentSuccess(
            eq(BOOKING_ID), eq(BigDecimal.ZERO), eq(BigDecimal.ZERO),
            isNull(), isNull(),
            eq(PARENT_ID), anyString(), anyString(), any(Instant.class), anyString());
    }

    @Test
    void stripeDecline_chargesCaptureFails_callsPersistFailureWithZeroReversal() {
        // Case B: balance 20, price 50 → Stripe for 30 → Stripe fails
        when(creditWalletService.getBalance(PARENT_ID)).thenReturn(new BigDecimal("20.00"));
        when(paymentGateway.chargeAndCapture(any(), any(), any(), any()))
            .thenThrow(new PaymentGatewayException("payment.lifecycleFailure"));

        service.onBookingAccepted(event(null));

        // Credit is NOT pre-debited before Stripe (design: credit commits atomically inside
        // persistPaymentSuccess only after Stripe succeeds) → creditToReverse = ZERO, no reversal entry
        verify(creditWalletService, never()).writeLedgerEntry(any(), any(), any(), any(), any());
        // P6: persistPaymentFailure called with ZERO reversal; it internally publishes
        // BookingDeclinedEvent which drives booking → DECLINED (AC 3 requirement)
        verify(persistenceService).persistPaymentFailure(
            eq(BOOKING_ID), eq(BigDecimal.ZERO),
            eq(PARENT_ID), anyString(), anyString(), any(Instant.class), anyString());
    }

    @Test
    void duplicateEvent_idempotencyNoOp() {
        when(bookingPaymentRepository.existsById(BOOKING_ID)).thenReturn(true);

        service.onBookingAccepted(event(null));

        verify(creditWalletService, never()).getBalance(any());
        verify(paymentGateway, never()).chargeAndCapture(any(), any(), any(), any());
        verify(persistenceService, never()).persistPaymentSuccess(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(persistenceService, never()).persistPaymentFailure(
            any(), any(), any(), any(), any(), any(), any());
    }
}
