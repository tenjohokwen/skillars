package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.BatchBookingAcceptedEvent;
import com.softropic.skillars.platform.booking.contract.BatchBookingCreatedResponse;
import com.softropic.skillars.platform.booking.contract.BatchBookingRequestedEvent;
import com.softropic.skillars.platform.booking.contract.BatchRuleViolationException;
import com.softropic.skillars.platform.booking.contract.BatchSlot;
import com.softropic.skillars.platform.booking.contract.CreateBatchRequest;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingBatch;
import com.softropic.skillars.platform.booking.repo.BookingBatchRepository;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingBatchServiceTest {

    @Mock BookingBatchRepository batchRepository;
    @Mock BookingRepository bookingRepository;
    @Mock CoachProfileRepository coachProfileRepository;
    @Mock PlayerProfileRepository playerProfileRepository;
    @Mock UserRepository userRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock ConfigService configService;
    @Mock BookingService bookingService;
    @Mock PlatformTransactionManager transactionManager;
    @Mock TransactionStatus transactionStatus;

    @InjectMocks BookingBatchService service;

    /**
     * @InjectMocks does not run @PostConstruct, so the REQUIRES_NEW TransactionTemplates acceptAll
     * depends on would be null. Initialise them by hand — the same shape ReviewModerationServiceTest
     * uses for its own hand-built TransactionTemplate.
     */
    @BeforeEach
    void initTemplates() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        service.initTransactionTemplates();
    }

    private static final long PARENT_ID = 9000001L;
    private static final long PLAYER_ID = 9000002L;
    private static final long COACH_USER_ID = 9000003L;
    private static final UUID COACH_ID = UUID.randomUUID();
    private static final UUID BATCH_ID = UUID.randomUUID();

    @Test
    void createBatch_validRequest_createsBatchAndBookings() {
        when(configService.getLong("booking.batch.maxSize")).thenReturn(5L);

        PlayerProfile player = new PlayerProfile();
        player.setParentId(PARENT_ID);
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));

        CoachProfile coach = buildActiveCoach();
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));

        BookingBatch savedBatch = new BookingBatch();
        savedBatch.setId(BATCH_ID);
        when(batchRepository.save(any())).thenReturn(savedBatch);
        when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(any())).thenReturn(Optional.empty());

        CreateBatchRequest req = buildRequest(2);
        BatchBookingCreatedResponse result = service.createBatch(PARENT_ID, req);

        assertThat(result.bookingCount()).isEqualTo(2);
        verify(batchRepository).save(any(BookingBatch.class));
        verify(bookingRepository, times(2)).save(any(Booking.class));
        verify(eventPublisher).publishEvent(any(BatchBookingRequestedEvent.class));
    }

    @Test
    void createBatch_exceedsMaxSize_throws400() {
        when(configService.getLong("booking.batch.maxSize")).thenReturn(5L);

        CreateBatchRequest req = buildRequest(6);
        assertThatThrownBy(() -> service.createBatch(PARENT_ID, req))
            .isInstanceOf(BatchRuleViolationException.class);

        verify(batchRepository, never()).save(any());
    }

    @Test
    void createBatch_parentDoesNotOwnPlayer_throws403() {
        when(configService.getLong("booking.batch.maxSize")).thenReturn(5L);

        PlayerProfile player = new PlayerProfile();
        player.setParentId(999L);
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));

        assertThatThrownBy(() -> service.createBatch(PARENT_ID, buildRequest(1)))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void createBatch_noLegacyPackPreflightCheck_batchIsCreatedRegardless() {
        // Story 11.2 Task 4 (decision a): batch bookings stay credit-wallet/Stripe-only —
        // the legacy pack-eligibility pre-flight check is dropped entirely, not migrated,
        // since it was already disconnected from the actual payment outcome (batch bookings
        // never carried a sessionPackPurchaseId and always settled via
        // PaymentLifecycleService.onBatchBookingAccepted's credit-wallet/Stripe branch).
        when(configService.getLong("booking.batch.maxSize")).thenReturn(5L);

        PlayerProfile player = new PlayerProfile();
        player.setParentId(PARENT_ID);
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(buildActiveCoach()));

        BookingBatch savedBatch = new BookingBatch();
        savedBatch.setId(BATCH_ID);
        when(batchRepository.save(any())).thenReturn(savedBatch);
        when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(any())).thenReturn(Optional.empty());

        BatchBookingCreatedResponse result = service.createBatch(PARENT_ID, buildRequest(1));

        assertThat(result.bookingCount()).isEqualTo(1);
        verify(batchRepository).save(any(BookingBatch.class));
    }

    @Test
    void acceptAll_coachOwnsBooking_transitionsAllRequestedAndPublishesEvent() {
        BookingBatch batch = new BookingBatch();
        batch.setId(BATCH_ID);
        batch.setCoachId(COACH_ID);
        batch.setParentId(PARENT_ID);
        batch.setStatus("PENDING");
        batch.setTotalAmount(BigDecimal.ZERO);
        when(batchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));

        CoachProfile coach = buildActiveCoach();
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        UUID bookingId1 = UUID.randomUUID();
        UUID bookingId2 = UUID.randomUUID();
        Instant slot1 = Instant.now().plus(3, ChronoUnit.DAYS);
        Instant slot2 = Instant.now().plus(4, ChronoUnit.DAYS);
        Booking b1 = new Booking(); b1.setId(bookingId1); b1.setStatus("REQUESTED");
        b1.setRequestedStartTime(slot1); b1.setRequestedEndTime(slot1.plus(1, ChronoUnit.HOURS));
        Booking b2 = new Booking(); b2.setId(bookingId2); b2.setStatus("REQUESTED");
        b2.setRequestedStartTime(slot2); b2.setRequestedEndTime(slot2.plus(1, ChronoUnit.HOURS));
        when(bookingRepository.findByBatchIdAndStatus(BATCH_ID, "REQUESTED")).thenReturn(List.of(b1, b2));
        // Deferred-14 AC4: each accept now locks the coach and re-checks for slot overlap first.
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(any(), any(), any(), any(), any()))
            .thenReturn(List.of());
        when(batchRepository.save(any())).thenReturn(batch);
        when(userRepository.findById(any())).thenReturn(Optional.empty());

        service.acceptAll(BATCH_ID, COACH_USER_ID);

        assertThat(batch.getStatus()).isEqualTo("FULLY_ACCEPTED");
        // Deferred-12 AC6: the batch flow now takes the same accept-then-initiate-payment step as
        // the single-booking flow, so its bookings rest in PAYMENT_PENDING where the payment
        // listener's PAYMENT_CAPTURED/PAYMENT_FAILED transitions are legal.
        verify(bookingService, times(2)).acceptAndInitiatePayment(any(), any());
        verify(eventPublisher).publishEvent(any(BatchBookingAcceptedEvent.class));
    }

    @Test
    void acceptAll_wrongCoach_throws403() {
        BookingBatch batch = new BookingBatch();
        batch.setId(BATCH_ID);
        batch.setCoachId(UUID.randomUUID());
        when(batchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));

        CoachProfile coach = buildActiveCoach();
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        assertThatThrownBy(() -> service.acceptAll(BATCH_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void updateBatchStatusFromBooking_allAccepted_setsFullyAccepted() {
        Booking b1 = new Booking(); b1.setStatus("ACCEPTED");
        Booking b2 = new Booking(); b2.setStatus("ACCEPTED");
        when(bookingRepository.findByBatchId(BATCH_ID)).thenReturn(List.of(b1, b2));

        BookingBatch batch = new BookingBatch();
        batch.setId(BATCH_ID);
        when(batchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));

        service.updateBatchStatusFromBooking(BATCH_ID);

        assertThat(batch.getStatus()).isEqualTo("FULLY_ACCEPTED");
        verify(batchRepository).save(batch);
    }

    @Test
    void updateBatchStatusFromBooking_allDeclined_setsDeclined() {
        Booking b1 = new Booking(); b1.setStatus("DECLINED");
        Booking b2 = new Booking(); b2.setStatus("CANCELLED_PARENT");
        when(bookingRepository.findByBatchId(BATCH_ID)).thenReturn(List.of(b1, b2));

        BookingBatch batch = new BookingBatch();
        batch.setId(BATCH_ID);
        when(batchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));

        service.updateBatchStatusFromBooking(BATCH_ID);

        assertThat(batch.getStatus()).isEqualTo("DECLINED");
    }

    @Test
    void updateBatchStatusFromBooking_mixed_setsPartiallyAccepted() {
        Booking b1 = new Booking(); b1.setStatus("ACCEPTED");
        Booking b2 = new Booking(); b2.setStatus("DECLINED");
        when(bookingRepository.findByBatchId(BATCH_ID)).thenReturn(List.of(b1, b2));

        BookingBatch batch = new BookingBatch();
        batch.setId(BATCH_ID);
        when(batchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));

        service.updateBatchStatusFromBooking(BATCH_ID);

        assertThat(batch.getStatus()).isEqualTo("PARTIALLY_ACCEPTED");
    }

    @Test
    void updateBatchStatusFromBooking_someStillRequested_doesNotUpdate() {
        Booking b1 = new Booking(); b1.setStatus("ACCEPTED");
        Booking b2 = new Booking(); b2.setStatus("REQUESTED");
        when(bookingRepository.findByBatchId(BATCH_ID)).thenReturn(List.of(b1, b2));

        service.updateBatchStatusFromBooking(BATCH_ID);

        verify(batchRepository, never()).save(any());
    }

    // ---- Helpers ----

    private CoachProfile buildActiveCoach() {
        CoachProfile coach = new CoachProfile();
        coach.setId(COACH_ID);
        coach.setUserId(COACH_USER_ID);
        coach.setStatus(CoachProfileStatus.ACTIVE);
        coach.setCanonicalTimezone("Europe/Berlin");
        coach.setDisplayName("Test Coach");
        return coach;
    }

    private CreateBatchRequest buildRequest(int slotCount) {
        Instant base = Instant.now().plus(2, ChronoUnit.DAYS);
        List<BatchSlot> slots = new java.util.ArrayList<>();
        for (int i = 0; i < slotCount; i++) {
            Instant start = base.plus(i, ChronoUnit.HOURS);
            slots.add(new BatchSlot(start, start.plus(1, ChronoUnit.HOURS)));
        }
        return new CreateBatchRequest(COACH_ID, PLAYER_ID, slots, BigDecimal.ZERO);
    }
}
