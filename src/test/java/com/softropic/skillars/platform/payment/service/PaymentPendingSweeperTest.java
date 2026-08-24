package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.booking.contract.BookingDeclinedEvent;
import com.softropic.skillars.platform.booking.contract.BookingEvent;
import com.softropic.skillars.platform.booking.contract.TransitionContext;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.repo.BookingPayment;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story Deferred-15 AC1/AC2. The negative cases carry the weight here: a sweeper that declines a
 * booking whose money already left the parent is worse than no sweeper at all.
 *
 * <p>Built by hand rather than with {@code @InjectMocks}, following BookingExpirySchedulerTest.
 */
@ExtendWith(MockitoExtension.class)
class PaymentPendingSweeperTest {

    private static final long PARENT_ID = 4101L;

    @Mock BookingRepository bookingRepository;
    @Mock BookingPaymentRepository bookingPaymentRepository;
    @Mock BookingService bookingService;
    @Mock ConfigService configService;
    @Mock CoachProfileRepository coachProfileRepository;
    @Mock UserRepository userRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock TransactionTemplate transactionTemplate;
    @Mock PessimisticLockRetryer lockRetryer;

    private PaymentPendingSweeper sweeper;

    @BeforeEach
    void setUp() {
        sweeper = new PaymentPendingSweeper(bookingRepository, bookingPaymentRepository, bookingService,
            configService, coachProfileRepository, userRepository, eventPublisher, transactionTemplate,
            new SimpleMeterRegistry(), lockRetryer);

        lenient().when(lockRetryer.withBoundedRetry(any()))
            .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get());
        lenient().when(configService.getBoundedLong(eq(PaymentPendingSweeper.GRACE_MINUTES_KEY),
            anyLong(), anyLong(), anyLong())).thenReturn(120L);
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });
        lenient().doAnswer(inv -> {
            Consumer<TransactionStatus> action = inv.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private Booking booking(UUID packPurchaseId) {
        Booking b = new Booking();
        b.setId(UUID.randomUUID());
        b.setParentId(PARENT_ID);
        b.setCoachId(UUID.randomUUID());
        b.setStatus("PAYMENT_PENDING");
        b.setSessionPackPurchaseId(packPurchaseId);
        b.setRequestedStartTime(Instant.now().plus(2, ChronoUnit.DAYS));
        b.setCanonicalTimezone("UTC");
        b.setUpdatedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        return b;
    }

    /**
     * UAT.3 review patch: sweepOne re-reads the booking under a PESSIMISTIC_WRITE lock, not the
     * plain findById it used before — otherwise a reserveCapture committing between its read and
     * its write would be silently overwritten with CHARGE_FAILED. Stub the locked read, or these
     * tests assert against a code path the sweeper no longer takes.
     */
    private void stage(Booking b) {
        when(bookingRepository.findPaymentPendingOlderThan(any())).thenReturn(List.of(b));
        when(bookingRepository.findByIdForUpdate(b.getId())).thenReturn(Optional.of(b));
    }

    @Test
    void packFundedStrandedBooking_isDeclinedWithChargeFailedPaymentRow() {
        Booking b = booking(UUID.randomUUID());
        stage(b);
        when(bookingPaymentRepository.findById(b.getId())).thenReturn(Optional.empty());
        when(coachProfileRepository.findById(b.getCoachId())).thenReturn(Optional.empty());
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.empty());

        sweeper.sweepStrandedPayments();

        ArgumentCaptor<BookingPayment> payment = ArgumentCaptor.forClass(BookingPayment.class);
        verify(bookingPaymentRepository).save(payment.capture());
        assertThat(payment.getValue().getStatus()).isEqualTo("CHARGE_FAILED");
        assertThat(payment.getValue().getCapturedAt()).as("nothing was captured").isNull();

        verify(bookingService).transition(eq(b.getId()), eq(BookingEvent.PAYMENT_FAILED), any(TransitionContext.class));
        verify(eventPublisher).publishEvent(any(BookingDeclinedEvent.class));
    }

    /**
     * UAT.3 AC5, inverting Deferred-15's behaviour. A credit-funded booking used to be reported and
     * left alone because nothing distinguished "never charged" from "charged and lost the record".
     * reserveCapture now writes a CAPTURE_PENDING row before either Stripe call, so no row at all
     * proves no charge was attempted — and the booking is decidable exactly like a pack-funded one.
     */
    @Test
    void creditFundedStrandedBookingWithNoPaymentRow_isNowDeclined() {
        Booking b = booking(null);
        stage(b);
        when(bookingPaymentRepository.findById(b.getId())).thenReturn(Optional.empty());
        when(coachProfileRepository.findById(b.getCoachId())).thenReturn(Optional.empty());
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.empty());

        sweeper.sweepStrandedPayments();

        ArgumentCaptor<BookingPayment> payment = ArgumentCaptor.forClass(BookingPayment.class);
        verify(bookingPaymentRepository).save(payment.capture());
        assertThat(payment.getValue().getStatus()).isEqualTo("CHARGE_FAILED");
        verify(bookingService).transition(eq(b.getId()), eq(BookingEvent.PAYMENT_FAILED), any(TransitionContext.class));
        verify(eventPublisher).publishEvent(any(BookingDeclinedEvent.class));
    }

    /**
     * The case that now protects real money, and the reason the funding-type test could be dropped:
     * a standing CAPTURE_PENDING row means an attempt reserved and never finished, so money may
     * already be at Stripe. It must survive the sweep untouched and escalate to an operator.
     */
    @Test
    void bookingWithOutstandingCaptureReservation_isReportedNotDeclined() {
        Booking b = booking(null);
        stage(b);
        when(bookingPaymentRepository.findById(b.getId()))
            .thenReturn(Optional.of(paymentRow("CAPTURE_PENDING")));

        sweeper.sweepStrandedPayments();

        verify(bookingPaymentRepository, never()).save(any());
        verify(bookingService, never()).transition(any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any(BookingDeclinedEvent.class));
    }

    @Test
    void packFundedBookingWithExistingPaymentRow_isReportedNotDeclined() {
        Booking b = booking(UUID.randomUUID());
        stage(b);
        when(bookingPaymentRepository.findById(b.getId())).thenReturn(Optional.of(paymentRow("CAPTURED")));

        sweeper.sweepStrandedPayments();

        verify(bookingPaymentRepository, never()).save(any());
        verify(bookingService, never()).transition(any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any(BookingDeclinedEvent.class));
    }

    private BookingPayment paymentRow(String status) {
        BookingPayment bp = new BookingPayment();
        bp.setStatus(status);
        return bp;
    }

    @Test
    void bookingSettledBetweenSelectAndSweep_isLeftAlone() {
        Booking b = booking(UUID.randomUUID());
        when(bookingRepository.findPaymentPendingOlderThan(any())).thenReturn(List.of(b));
        Booking settled = booking(b.getSessionPackPurchaseId());
        settled.setId(b.getId());
        settled.setStatus("CONFIRMED");
        when(bookingRepository.findByIdForUpdate(b.getId())).thenReturn(Optional.of(settled));

        sweeper.sweepStrandedPayments();

        verify(bookingPaymentRepository, never()).save(any());
        verify(bookingService, never()).transition(any(), any(), any());
    }

    @Test
    void graceWindow_comesFromConfigAndIsAppliedToTheQueryThreshold() {
        when(bookingRepository.findPaymentPendingOlderThan(any())).thenReturn(List.of());

        sweeper.sweepStrandedPayments();

        ArgumentCaptor<Instant> threshold = ArgumentCaptor.forClass(Instant.class);
        verify(bookingRepository).findPaymentPendingOlderThan(threshold.capture());
        assertThat(threshold.getValue())
            .isBetween(Instant.now().minus(121, ChronoUnit.MINUTES), Instant.now().minus(119, ChronoUnit.MINUTES));
    }

    @Test
    void oneFailingBookingDoesNotAbortTheSweep() {
        Booking bad = booking(UUID.randomUUID());
        Booking good = booking(UUID.randomUUID());
        when(bookingRepository.findPaymentPendingOlderThan(any())).thenReturn(List.of(bad, good));
        when(bookingRepository.findByIdForUpdate(bad.getId())).thenThrow(new IllegalStateException("boom"));
        when(bookingRepository.findByIdForUpdate(good.getId())).thenReturn(Optional.of(good));
        when(bookingPaymentRepository.findById(good.getId())).thenReturn(Optional.empty());
        when(coachProfileRepository.findById(good.getCoachId())).thenReturn(Optional.empty());
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.empty());

        sweeper.sweepStrandedPayments();

        verify(bookingService).transition(eq(good.getId()), eq(BookingEvent.PAYMENT_FAILED), any());
    }
}
