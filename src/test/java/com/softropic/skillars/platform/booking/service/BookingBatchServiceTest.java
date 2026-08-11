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
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindowRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.booking.contract.BookingError;
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
import java.time.Duration;
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
    @Mock SessionDurationResolver sessionDurationResolver;
    @Mock CoachAvailabilityWindowRepository coachAvailabilityWindowRepository;
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
        // UAT.2 AC4: every buildRequest() slot is exactly one hour and inside the coach's
        // availability. Lenient because the tests that fail earlier (batch size, ownership,
        // inactive coach) never reach either check.
        lenient().when(sessionDurationResolver.resolve(COACH_ID)).thenReturn(Duration.ofHours(1));
        lenient().when(bookingService.isSlotWithinAvailabilityWindow(any(), any(), any()))
            .thenReturn(true);
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

    // ---- UAT.2 AC4: the three checks the batch path never had ----

    /**
     * The regression that never had coverage: createBatch never called
     * isSlotWithinAvailabilityWindow at all, so a batch could book ten slots entirely outside the
     * coach's availability — something the single-booking path has rejected since day one.
     */
    @Test
    void createBatch_slotOutsideCoachAvailability_isRejected() {
        when(configService.getLong("booking.batch.maxSize")).thenReturn(5L);
        stubOwnershipAndActiveCoach();
        when(bookingService.isSlotWithinAvailabilityWindow(any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.createBatch(PARENT_ID, buildRequest(2)))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("not within coach availability");

        verify(batchRepository, never()).save(any());
    }

    /**
     * The stub above returns false for every slot, so on its own it only proves the FIRST slot is
     * checked. Here the first slot passes and the second does not: the window check must run for
     * every slot in the batch, which is what the loop position — rather than a pre-loop check —
     * buys.
     */
    @Test
    void createBatch_laterSlotOutsideCoachAvailability_isRejected() {
        when(configService.getLong("booking.batch.maxSize")).thenReturn(5L);
        stubOwnershipAndActiveCoach();

        CreateBatchRequest req = buildRequest(2);
        Instant secondSlotStart = req.slots().get(1).requestedStartTime();
        when(bookingService.isSlotWithinAvailabilityWindow(any(), any(), any()))
            .thenAnswer(inv -> !secondSlotStart.equals(inv.getArgument(0)));

        assertThatThrownBy(() -> service.createBatch(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("not within coach availability");

        verify(batchRepository, never()).save(any());
        verify(bookingService, times(2)).isSlotWithinAvailabilityWindow(any(), any(), any());
    }

    /** The window list is fetched ONCE for the batch, not once per slot. */
    @Test
    void createBatch_fetchesTheAvailabilityWindowsOncePerBatchNotPerSlot() {
        when(configService.getLong("booking.batch.maxSize")).thenReturn(10L);
        stubOwnershipAndActiveCoach();
        BookingBatch savedBatch = new BookingBatch();
        savedBatch.setId(BATCH_ID);
        when(batchRepository.save(any())).thenReturn(savedBatch);
        when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(any())).thenReturn(Optional.empty());

        service.createBatch(PARENT_ID, buildRequest(10));

        verify(coachAvailabilityWindowRepository, times(1)).findByCoachId(COACH_ID);
        verify(sessionDurationResolver, times(1)).resolve(COACH_ID);
        verify(bookingRepository, times(10)).save(any(Booking.class));
    }

    @Test
    void createBatch_slotOfTheWrongLength_isRejected() {
        when(configService.getLong("booking.batch.maxSize")).thenReturn(5L);
        stubOwnershipAndActiveCoach();

        Instant base = Instant.now().plus(2, ChronoUnit.DAYS);
        CreateBatchRequest req = new CreateBatchRequest(COACH_ID, PLAYER_ID, List.of(
            new BatchSlot(base, base.plus(1, ChronoUnit.HOURS)),
            new BatchSlot(base.plus(2, ChronoUnit.HOURS), base.plus(5, ChronoUnit.HOURS))
        ), BigDecimal.ZERO);

        assertThatThrownBy(() -> service.createBatch(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("session length");

        verify(batchRepository, never()).save(any());
    }

    /**
     * Distinct start times were the only cross-slot rule, and they do not imply non-overlapping:
     * 09:00-10:00 and 09:30-10:30 have different starts and both used to pass.
     */
    @Test
    void createBatch_twoOverlappingSlotsWithDistinctStarts_isRejected() {
        when(configService.getLong("booking.batch.maxSize")).thenReturn(5L);
        stubOwnershipAndActiveCoach();
        when(sessionDurationResolver.resolve(COACH_ID)).thenReturn(Duration.ofMinutes(60));

        Instant base = Instant.now().plus(2, ChronoUnit.DAYS);
        CreateBatchRequest req = new CreateBatchRequest(COACH_ID, PLAYER_ID, List.of(
            new BatchSlot(base, base.plus(60, ChronoUnit.MINUTES)),
            new BatchSlot(base.plus(30, ChronoUnit.MINUTES), base.plus(90, ChronoUnit.MINUTES))
        ), BigDecimal.ZERO);

        assertThatThrownBy(() -> service.createBatch(PARENT_ID, req))
            .isInstanceOf(BatchRuleViolationException.class)
            .hasMessageContaining("booking.overlappingSlots");

        verify(batchRepository, never()).save(any());
    }

    /** Slots that merely touch (one ends exactly where the next starts) are not overlapping. */
    @Test
    void createBatch_backToBackSlots_areAccepted() {
        when(configService.getLong("booking.batch.maxSize")).thenReturn(5L);
        stubOwnershipAndActiveCoach();
        BookingBatch savedBatch = new BookingBatch();
        savedBatch.setId(BATCH_ID);
        when(batchRepository.save(any())).thenReturn(savedBatch);
        when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(any())).thenReturn(Optional.empty());

        assertThat(service.createBatch(PARENT_ID, buildRequest(3)).bookingCount()).isEqualTo(3);
    }

    private void stubOwnershipAndActiveCoach() {
        PlayerProfile player = new PlayerProfile();
        player.setParentId(PARENT_ID);
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(buildActiveCoach()));
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
        // Deferred-15 AC5: the trailing transaction now re-reads every booking in the batch and runs
        // the same formula the listener does. Here that read returns what the per-booking commits
        // would have written — both bookings settled into PAYMENT_PENDING.
        when(bookingRepository.findByBatchId(BATCH_ID))
            .thenReturn(List.of(bookingInStatus("PAYMENT_PENDING"), bookingInStatus("PAYMENT_PENDING")));

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

    /**
     * Deferred-15 AC5: acceptAll used to compare acceptedIds.size() against the REQUESTED subset
     * captured at loop start, so a booking declined individually BEFORE acceptAll ran was invisible
     * to it and the batch read FULLY_ACCEPTED. The trailing transaction now counts every booking in
     * the batch, exactly as the AFTER_COMMIT listener always did.
     */
    @Test
    void acceptAll_batchAlreadyContainsADeclinedBooking_endsPartiallyAccepted() {
        BookingBatch batch = new BookingBatch();
        batch.setId(BATCH_ID);
        batch.setCoachId(COACH_ID);
        batch.setParentId(PARENT_ID);
        batch.setStatus("PENDING");
        batch.setTotalAmount(BigDecimal.ZERO);
        when(batchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));

        CoachProfile coach = buildActiveCoach();
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));

        Instant slot = Instant.now().plus(3, ChronoUnit.DAYS);
        Booking requested = new Booking();
        requested.setId(UUID.randomUUID());
        requested.setStatus("REQUESTED");
        requested.setRequestedStartTime(slot);
        requested.setRequestedEndTime(slot.plus(1, ChronoUnit.HOURS));
        // Only the REQUESTED one is offered to the loop — the DECLINED sibling never appears there,
        // which is precisely why the old formula could not see it.
        when(bookingRepository.findByBatchIdAndStatus(BATCH_ID, "REQUESTED")).thenReturn(List.of(requested));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(bookingRepository.findOverlappingBookings(any(), any(), any(), any(), any())).thenReturn(List.of());
        when(bookingRepository.findByBatchId(BATCH_ID))
            .thenReturn(List.of(bookingInStatus("PAYMENT_PENDING"), bookingInStatus("DECLINED")));
        when(batchRepository.save(any())).thenReturn(batch);
        when(userRepository.findById(any())).thenReturn(Optional.empty());

        service.acceptAll(BATCH_ID, COACH_USER_ID);

        assertThat(batch.getStatus())
            .as("one of the two bookings in this batch is DECLINED — the batch is not fully accepted")
            .isEqualTo("PARTIALLY_ACCEPTED");
    }

    /** Deferred-15 AC4: a suspended coach cannot accept a batch, and fails the batch as a whole. */
    @Test
    void acceptAll_suspendedCoach_throwsCoachUnavailableAndAcceptsNothing() {
        BookingBatch batch = new BookingBatch();
        batch.setId(BATCH_ID);
        batch.setCoachId(COACH_ID);
        batch.setParentId(PARENT_ID);
        batch.setStatus("PENDING");
        when(batchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));

        CoachProfile suspended = buildActiveCoach();
        suspended.setStatus(CoachProfileStatus.SUSPENDED);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(suspended));

        assertThatThrownBy(() -> service.acceptAll(BATCH_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.COACH_UNAVAILABLE));

        verify(bookingService, never()).acceptAndInitiatePayment(any(), any());
        assertThat(batch.getStatus()).isEqualTo("PENDING");
    }

    /**
     * Deferred-15 AC5, review follow-up: the degenerate input. Exercised through
     * updateBatchStatusFromBooking's sibling path — an empty batch must not read FULLY_ACCEPTED via
     * a vacuous 0 == 0. That method's own early-return means no write happens at all here, which is
     * the behaviour being pinned.
     */
    @Test
    void updateBatchStatusFromBooking_emptyBatch_writesNothing() {
        when(bookingRepository.findByBatchId(BATCH_ID)).thenReturn(List.of());

        service.updateBatchStatusFromBooking(BATCH_ID);

        verify(batchRepository, never()).save(any());
    }

    private Booking bookingInStatus(String status) {
        Booking b = new Booking();
        b.setId(UUID.randomUUID());
        b.setStatus(status);
        return b;
    }

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
