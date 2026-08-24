package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.platform.payment.repo.BookingPayment;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story UAT.3 AC1. {@code reserveCapture} is the gate every Stripe charge for a booking now passes
 * through, and the invariant the sweeper reads rests entirely on it: for a booking still in
 * PAYMENT_PENDING, no {@code booking_payments} row proves no Stripe call was attempted.
 *
 * <p>The negative cases carry the weight. A reservation that writes a row when it should not have
 * would make a genuinely stranded booking look charged and block it from ever being swept; a
 * reservation that returns RESERVED when it should not would let the caller charge a booking the
 * parent has already cancelled.
 */
@ExtendWith(MockitoExtension.class)
class CaptureReservationTest {

    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final UUID BATCH_ID = UUID.randomUUID();
    private static final BigDecimal CREDIT = new BigDecimal("20.00");
    private static final BigDecimal STRIPE = new BigDecimal("30.00");

    @Mock CreditWalletService creditWalletService;
    @Mock BookingPaymentRepository bookingPaymentRepository;
    @Mock BookingRepository bookingRepository;
    @Mock BookingService bookingService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Spy MeterRegistry meterRegistry = new SimpleMeterRegistry();
    @Mock PessimisticLockRetryer lockRetryer;

    @InjectMocks BookingPaymentPersistenceService service;

    @BeforeEach
    void setUpLockRetryer() {
        lenient().when(lockRetryer.withBoundedRetry(any()))
            .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get());
    }

    private Booking booking(String status) {
        Booking b = new Booking();
        b.setId(BOOKING_ID);
        b.setStatus(status);
        return b;
    }

    private BookingPayment paymentRow(String status) {
        BookingPayment bp = new BookingPayment();
        bp.setBookingId(BOOKING_ID);
        bp.setStatus(status);
        return bp;
    }

    @Test
    void pendingBookingWithNoPaymentRow_reservesACapturePendingRowCarryingTheIntendedAmounts() {
        when(bookingRepository.findByIdForUpdate(BOOKING_ID)).thenReturn(Optional.of(booking("PAYMENT_PENDING")));
        when(bookingPaymentRepository.findById(BOOKING_ID)).thenReturn(Optional.empty());

        assertThat(service.reserveCapture(BOOKING_ID, CREDIT, STRIPE, BATCH_ID))
            .isEqualTo(CaptureReservation.RESERVED);

        ArgumentCaptor<BookingPayment> saved = ArgumentCaptor.forClass(BookingPayment.class);
        verify(bookingPaymentRepository).save(saved.capture());
        BookingPayment row = saved.getValue();
        assertThat(row.getBookingId()).isEqualTo(BOOKING_ID);
        assertThat(row.getStatus()).isEqualTo("CAPTURE_PENDING");
        assertThat(row.getCreditDebited()).isEqualByComparingTo(CREDIT);
        assertThat(row.getStripeCharged()).isEqualByComparingTo(STRIPE);
        assertThat(row.getBatchPaymentIntentId()).isEqualTo(BATCH_ID);
        assertThat(row.getCapturedAt()).as("nothing has been captured yet").isNull();
        assertThat(row.getStripePaymentIntentId()).as("no payment intent exists yet").isNull();
    }

    /**
     * The interlock's other half: a parent cancellation that won the race leaves the booking in
     * CANCELLED_PARENT, and the caller must return before touching Stripe. Writing a row here would
     * also be wrong twice over — it would record a charge that never happened.
     */
    @Test
    void bookingNoLongerPending_isRefusedAndWritesNoRow() {
        when(bookingRepository.findByIdForUpdate(BOOKING_ID)).thenReturn(Optional.of(booking("CANCELLED_PARENT")));

        assertThat(service.reserveCapture(BOOKING_ID, CREDIT, STRIPE, null))
            .isEqualTo(CaptureReservation.BOOKING_NOT_PENDING);

        verify(bookingPaymentRepository, never()).save(any());
    }

    @Test
    void bookingGone_isRefusedAndWritesNoRow() {
        when(bookingRepository.findByIdForUpdate(BOOKING_ID)).thenReturn(Optional.empty());

        assertThat(service.reserveCapture(BOOKING_ID, CREDIT, STRIPE, null))
            .isEqualTo(CaptureReservation.BOOKING_NOT_PENDING);

        verify(bookingPaymentRepository, never()).save(any());
    }

    /**
     * A standing CAPTURE_PENDING row means a previous attempt reserved and never finished, so its
     * outcome at Stripe is unknown. It must be distinguished from ALREADY_SETTLED — the caller logs
     * it at ERROR and it is what the sweeper escalates on — and must not be overwritten.
     */
    @Test
    void outstandingReservation_isCaptureUnconfirmedAndWritesNoRow() {
        when(bookingRepository.findByIdForUpdate(BOOKING_ID)).thenReturn(Optional.of(booking("PAYMENT_PENDING")));
        when(bookingPaymentRepository.findById(BOOKING_ID)).thenReturn(Optional.of(paymentRow("CAPTURE_PENDING")));

        assertThat(service.reserveCapture(BOOKING_ID, CREDIT, STRIPE, null))
            .isEqualTo(CaptureReservation.CAPTURE_UNCONFIRMED);

        verify(bookingPaymentRepository, never()).save(any());
    }

    @Test
    void terminalPaymentRow_isAlreadySettledAndWritesNoRow() {
        when(bookingRepository.findByIdForUpdate(BOOKING_ID)).thenReturn(Optional.of(booking("PAYMENT_PENDING")));
        when(bookingPaymentRepository.findById(BOOKING_ID)).thenReturn(Optional.of(paymentRow("CAPTURED")));

        assertThat(service.reserveCapture(BOOKING_ID, CREDIT, STRIPE, null))
            .isEqualTo(CaptureReservation.ALREADY_SETTLED);

        verify(bookingPaymentRepository, never()).save(any());
    }

    /**
     * Deferred-15 D3 assumes no write path touches a PAYMENT_PENDING booking row outside
     * settlement. reserveCapture takes a PESSIMISTIC_WRITE lock on that row and must write nothing
     * to it, or that accepted risk becomes live.
     */
    @Test
    void reservingNeverWritesToTheBookingRow() {
        when(bookingRepository.findByIdForUpdate(BOOKING_ID)).thenReturn(Optional.of(booking("PAYMENT_PENDING")));
        when(bookingPaymentRepository.findById(BOOKING_ID)).thenReturn(Optional.empty());

        service.reserveCapture(BOOKING_ID, CREDIT, STRIPE, null);

        verify(bookingRepository, never()).save(any());
        verify(bookingService, never()).transition(any(), any(), any());
    }
}
