package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.BookingAcceptedEvent;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachPricingRepository;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.BookingPayment;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
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
    // UAT.3 AC3: abortSettle builds a Counter against this. A plain @Mock MeterRegistry returns
    // null from Counter.builder(...).register(...), so a real registry is used and @Spy is what
    // makes @InjectMocks pass it to the constructor.
    @Spy MeterRegistry meterRegistry = new SimpleMeterRegistry();

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
        // UAT.3 AC3: "a row exists" no longer means "settled", so the guards read the row and its
        // status rather than existsById. No row at all is the ordinary first-delivery case; the
        // two tests that need a row override this, hence lenient.
        lenient().when(bookingPaymentRepository.findById(BOOKING_ID)).thenReturn(Optional.empty());
    }

    /** Stubs the pre-capture reservation as granted — required on every path that reaches Stripe. */
    private void reservationGranted() {
        when(persistenceService.reserveCapture(eq(BOOKING_ID), any(), any(), isNull()))
            .thenReturn(CaptureReservation.RESERVED);
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
    void caseAB_boundary_balanceExactlyEqualsSessionPrice_zeroStripeCharge() {
        when(creditWalletService.getBalance(PARENT_ID)).thenReturn(new BigDecimal("50.00"));

        service.onBookingAccepted(event(null));

        verify(paymentGateway, never()).chargeAndCapture(any(), any(), any(), any());
        // creditToUse = min(50, 50) = 50; stripeAmount = 0
        verify(persistenceService).persistPaymentSuccess(
            eq(BOOKING_ID), eq(SESSION_PRICE), eq(BigDecimal.ZERO),
            isNull(), isNull(),
            eq(PARENT_ID), anyString(), anyString(), any(Instant.class), anyString());
    }

    @Test
    void caseB_partialCredit_stripeChargedForDeficit() {
        when(creditWalletService.getBalance(PARENT_ID)).thenReturn(new BigDecimal("20.00"));
        reservationGranted();
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
        reservationGranted();
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
    void packBasedBooking_deductSessionFails_callsPersistFailureWithZeroReversal() {
        UUID packId = UUID.randomUUID();
        doThrow(new PaymentGatewayException("payment.packExhausted"))
            .when(packSessionService).deductSession(packId);

        service.onBookingAccepted(event(packId));

        verify(creditWalletService, never()).getBalance(any());
        verify(paymentGateway, never()).chargeAndCapture(any(), any(), any(), any());
        verify(persistenceService, never()).persistPaymentSuccess(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(persistenceService).persistPaymentFailure(
            eq(BOOKING_ID), eq(BigDecimal.ZERO),
            eq(PARENT_ID), anyString(), anyString(), any(Instant.class), anyString());
    }

    @Test
    void packBasedBooking_deductSessionFailsWithNonPaymentGatewayException_callsPersistFailureWithZeroReversal() {
        UUID packId = UUID.randomUUID();
        doThrow(new IllegalStateException("simulated repository failure"))
            .when(packSessionService).deductSession(packId);

        service.onBookingAccepted(event(packId));

        verify(creditWalletService, never()).getBalance(any());
        verify(paymentGateway, never()).chargeAndCapture(any(), any(), any(), any());
        verify(persistenceService, never()).persistPaymentSuccess(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(persistenceService).persistPaymentFailure(
            eq(BOOKING_ID), eq(BigDecimal.ZERO),
            eq(PARENT_ID), anyString(), anyString(), any(Instant.class), anyString());
    }

    @Test
    void stripeDecline_chargesCaptureFails_callsPersistFailureWithZeroReversal() {
        // Case B: balance 20, price 50 → Stripe for 30 → Stripe fails
        when(creditWalletService.getBalance(PARENT_ID)).thenReturn(new BigDecimal("20.00"));
        reservationGranted();
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
        when(bookingPaymentRepository.findById(BOOKING_ID)).thenReturn(Optional.of(payment("CAPTURED")));

        service.onBookingAccepted(event(null));

        verify(creditWalletService, never()).getBalance(any());
        verify(paymentGateway, never()).chargeAndCapture(any(), any(), any(), any());
        verify(persistenceService, never()).persistPaymentSuccess(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(persistenceService, never()).persistPaymentFailure(
            any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * UAT.3 AC3. A CAPTURE_PENDING row is NOT a duplicate delivery to be skipped silently — a prior
     * attempt reserved and never finished, so money may already be at Stripe. The settle must abort
     * without charging again (double-charging the parent) and must raise the alertable counter,
     * which is the only signal an operator gets before the sweeper escalates it.
     */
    @Test
    void outstandingReservation_isNotTreatedAsDuplicate_neverRechargesAndCountsCaptureUnconfirmed() {
        when(bookingPaymentRepository.findById(BOOKING_ID))
            .thenReturn(Optional.of(payment("CAPTURE_PENDING")));

        service.onBookingAccepted(event(null));

        verify(paymentGateway, never()).chargeAndCapture(any(), any(), any(), any());
        verify(persistenceService, never()).reserveCapture(any(), any(), any(), any());
        verify(persistenceService, never()).persistPaymentSuccess(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        Counter counter = meterRegistry.find("booking.payment.settle_aborted")
            .tag("reason", "capture_unconfirmed").counter();
        assertThat(counter).as("an outstanding reservation must be alertable, not silent").isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    /**
     * UAT.3 AC3. The reservation is the interlock: when it is refused — a parent cancellation won
     * the race, so the booking is no longer PAYMENT_PENDING — the gateway must never be reached.
     */
    @Test
    void reservationRefused_bookingNoLongerPending_stripeIsNeverCalled() {
        when(creditWalletService.getBalance(PARENT_ID)).thenReturn(BigDecimal.ZERO);
        when(persistenceService.reserveCapture(eq(BOOKING_ID), any(), any(), isNull()))
            .thenReturn(CaptureReservation.BOOKING_NOT_PENDING);

        service.onBookingAccepted(event(null));

        verify(paymentGateway, never()).chargeAndCapture(any(), any(), any(), any());
        verify(persistenceService, never()).persistPaymentSuccess(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(persistenceService, never()).persistPaymentFailure(
            any(), any(), any(), any(), any(), any(), any());
    }

    private BookingPayment payment(String status) {
        BookingPayment bp = new BookingPayment();
        bp.setBookingId(BOOKING_ID);
        bp.setStatus(status);
        return bp;
    }
}
