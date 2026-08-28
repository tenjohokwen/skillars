package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import jakarta.persistence.EntityManager;
import com.softropic.skillars.platform.booking.contract.BatchAcceptResult;
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
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindow;
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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.server.ResponseStatusException;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
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
    @Mock PessimisticLockRetryer lockRetryer;
    @Mock EntityManager entityManager;

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
        lenient().when(lockRetryer.withBoundedRetry(any()))
            .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get());
        // Deferred-78 AC1: createBatch now locks the coach row before its fresh re-check. Lenient
        // because the tests that fail earlier (batch size, ownership, inactive coach, first-pass
        // duration/availability/overlap checks) never reach the lock at all.
        lenient().when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(buildActiveCoach()));
        // UAT.2 AC4: every buildRequest() slot is exactly one hour and inside the coach's
        // availability. Lenient because the tests that fail earlier (batch size, ownership,
        // inactive coach) never reach either check.
        lenient().when(sessionDurationResolver.resolve(COACH_ID)).thenReturn(Duration.ofHours(1));
        lenient().when(bookingService.isSlotWithinAvailabilityWindow(any(), any(), any(), any()))
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

    // ---- skillars-deferred-72 AC4: batch-level availability-staleness guard ----

    @Test
    void createBatch_matchingAvailabilitySignature_succeeds() {
        when(configService.getLong("booking.batch.maxSize")).thenReturn(5L);
        stubOwnershipAndActiveCoach();

        CoachAvailabilityWindow window = new CoachAvailabilityWindow();
        window.setId(UUID.randomUUID());
        window.setCoachId(COACH_ID);
        window.setDayOfWeek((short) 1);
        window.setStartTime(java.time.LocalTime.of(0, 0));
        window.setEndTime(java.time.LocalTime.of(23, 59));
        window.setCanonicalTimezone("UTC");
        when(coachAvailabilityWindowRepository.findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(COACH_ID)).thenReturn(List.of(window));

        BookingBatch savedBatch = new BookingBatch();
        savedBatch.setId(BATCH_ID);
        when(batchRepository.save(any())).thenReturn(savedBatch);
        when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(any())).thenReturn(Optional.empty());

        String currentSignature =
            AvailabilityService.computeAvailabilitySignature(List.of(window), Duration.ofHours(1));
        CreateBatchRequest base = buildRequest(2);
        CreateBatchRequest req = new CreateBatchRequest(base.coachId(), base.playerId(), base.slots(),
            base.totalAmount(), currentSignature);

        assertThat(service.createBatch(PARENT_ID, req).bookingCount()).isEqualTo(2);
        verify(batchRepository).save(any(BookingBatch.class));
    }

    @Test
    void createBatch_staleAvailabilitySignature_throwsAvailabilityChangedBeforePersisting() {
        when(configService.getLong("booking.batch.maxSize")).thenReturn(5L);
        stubOwnershipAndActiveCoach();

        CreateBatchRequest base = buildRequest(2);
        CreateBatchRequest req = new CreateBatchRequest(base.coachId(), base.playerId(), base.slots(),
            base.totalAmount(), "a-stale-signature-that-cannot-match");

        assertThatThrownBy(() -> service.createBatch(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(ex -> assertThat(((OperationNotAllowedException) ex).getErrorCode())
                .isEqualTo(BookingError.AVAILABILITY_CHANGED));

        verify(batchRepository, never()).save(any());
        verify(bookingRepository, never()).save(any());
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
        when(bookingService.isSlotWithinAvailabilityWindow(any(), any(), any(), any())).thenReturn(false);

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
        when(bookingService.isSlotWithinAvailabilityWindow(any(), any(), any(), any()))
            .thenAnswer(inv -> !secondSlotStart.equals(inv.getArgument(0)));

        assertThatThrownBy(() -> service.createBatch(PARENT_ID, req))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("not within coach availability");

        verify(batchRepository, never()).save(any());
        verify(bookingService, times(2)).isSlotWithinAvailabilityWindow(any(), any(), any(), any());
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

        // skillars-deferred-69 AC7: now resolved twice total (once up front, once again immediately
        // before persist, to narrow the staleness window) — still O(1) in the slot count, not O(n),
        // which is the invariant this test's name pins. Was verify(times(1)) before AC7.
        verify(coachAvailabilityWindowRepository, times(2)).findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(COACH_ID);
        verify(sessionDurationResolver, times(2)).resolve(COACH_ID);
        verify(bookingRepository, times(10)).save(any(Booking.class));
    }

    /**
     * skillars-deferred-69 AC7: a coach edit landing between the initial resolve and the persist
     * point (simulated here by the second isSlotWithinAvailabilityWindow call returning false, where
     * the first — the initial pass — returned true) must abort the whole batch, and no booking or
     * batch row may be written.
     */
    @Test
    void createBatch_availabilityNarrowsBetweenInitialResolveAndPersist_abortsWholeBatch() {
        when(configService.getLong("booking.batch.maxSize")).thenReturn(10L);
        stubOwnershipAndActiveCoach();
        when(bookingService.isSlotWithinAvailabilityWindow(any(), any(), any(), any()))
            .thenReturn(true, false);

        assertThatThrownBy(() -> service.createBatch(PARENT_ID, buildRequest(1)))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.SLOT_OUTSIDE_AVAILABILITY));

        verify(coachAvailabilityWindowRepository, times(2)).findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(COACH_ID);
        verify(batchRepository, never()).save(any());
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void createBatch_slotOfTheWrongLength_isRejected() {
        when(configService.getLong("booking.batch.maxSize")).thenReturn(5L);
        stubOwnershipAndActiveCoach();

        Instant base = Instant.now().plus(2, ChronoUnit.DAYS);
        CreateBatchRequest req = new CreateBatchRequest(COACH_ID, PLAYER_ID, List.of(
            new BatchSlot(base, base.plus(1, ChronoUnit.HOURS)),
            new BatchSlot(base.plus(2, ChronoUnit.HOURS), base.plus(5, ChronoUnit.HOURS))
        ), BigDecimal.ZERO, null);

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
        ), BigDecimal.ZERO, null);

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

    // ---- UAT.5 AC1: self-registered adult PLAYER can create a batch for themselves ----

    @Test
    void createBatch_selfRegisteredPlayerBooksForThemselves_succeedsAndWritesOwnUserIdAsParentId() {
        long selfPlayerUserId = 9000005L;
        when(configService.getLong("booking.batch.maxSize")).thenReturn(5L);

        PlayerProfile selfPlayer = new PlayerProfile();
        selfPlayer.setUserId(selfPlayerUserId);
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(selfPlayer));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(buildActiveCoach()));

        BookingBatch savedBatch = new BookingBatch();
        savedBatch.setId(BATCH_ID);
        when(batchRepository.save(any())).thenAnswer(inv -> {
            BookingBatch b = inv.getArgument(0);
            assertThat(b.getParentId())
                .as("a self-booking player's own userId is written into the opaque parent_id column")
                .isEqualTo(selfPlayerUserId);
            return savedBatch;
        });
        when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(any())).thenReturn(Optional.empty());

        BatchBookingCreatedResponse result = service.createBatch(selfPlayerUserId, buildRequest(2));

        assertThat(result.bookingCount()).isEqualTo(2);
        verify(batchRepository).save(any(BookingBatch.class));
    }

    /** Self-registered player must not be able to book using someone else's playerId. */
    @Test
    void createBatch_selfRegisteredPlayerUsesSomeoneElsesPlayerId_isRejected() {
        when(configService.getLong("booking.batch.maxSize")).thenReturn(5L);

        PlayerProfile someoneElsesPlayer = new PlayerProfile();
        someoneElsesPlayer.setUserId(999L);
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(someoneElsesPlayer));

        assertThatThrownBy(() -> service.createBatch(9000005L, buildRequest(1)))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("does not own this profile");
        verify(batchRepository, never()).save(any());
    }

    /** Exercises the "if" branch from a parent caller against a self-owned player. */
    @Test
    void createBatch_parentAttemptsToBookASelfOwnedPlayer_isRejected() {
        when(configService.getLong("booking.batch.maxSize")).thenReturn(5L);

        PlayerProfile selfOwnedPlayer = new PlayerProfile();
        selfOwnedPlayer.setUserId(9000005L);
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(selfOwnedPlayer));

        assertThatThrownBy(() -> service.createBatch(PARENT_ID, buildRequest(1)))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("does not own this profile");
        verify(batchRepository, never()).save(any());
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
        // skillars-deferred-69 AC6: acceptAll's trailing transaction now takes the locked read.
        when(batchRepository.findByIdForUpdate(BATCH_ID)).thenReturn(Optional.of(batch));

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
        // skillars-deferred-69 AC6: updateBatchStatusFromBooking now takes the locked read too.
        when(batchRepository.findByIdForUpdate(BATCH_ID)).thenReturn(Optional.of(batch));

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
        // skillars-deferred-69 AC6: updateBatchStatusFromBooking now takes the locked read too.
        when(batchRepository.findByIdForUpdate(BATCH_ID)).thenReturn(Optional.of(batch));

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
        // skillars-deferred-69 AC6: updateBatchStatusFromBooking now takes the locked read too.
        when(batchRepository.findByIdForUpdate(BATCH_ID)).thenReturn(Optional.of(batch));

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
        // skillars-deferred-69 AC6: acceptAll's trailing transaction now takes the locked read.
        when(batchRepository.findByIdForUpdate(BATCH_ID)).thenReturn(Optional.of(batch));

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

    /**
     * Deferred-34 AC1/AC5: acceptAll must report a per-booking result, not just an aggregate batch
     * status. Booking A has no collision and accepts; booking B collides and fails —
     * mutation-verified against resolveFailureCode.
     */
    @Test
    void acceptAll_oneSlotCollides_returnsOneAcceptedOneFailedResult() {
        BookingBatch batch = new BookingBatch();
        batch.setId(BATCH_ID);
        batch.setCoachId(COACH_ID);
        batch.setParentId(PARENT_ID);
        batch.setStatus("PENDING");
        batch.setTotalAmount(BigDecimal.ZERO);
        when(batchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));
        // skillars-deferred-69 AC6: acceptAll's trailing transaction now takes the locked read.
        when(batchRepository.findByIdForUpdate(BATCH_ID)).thenReturn(Optional.of(batch));

        CoachProfile coach = buildActiveCoach();
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));

        Instant slotA = Instant.now().plus(3, ChronoUnit.DAYS);
        Instant slotB = Instant.now().plus(4, ChronoUnit.DAYS);
        Booking bookingA = new Booking();
        bookingA.setId(UUID.randomUUID());
        bookingA.setStatus("REQUESTED");
        bookingA.setRequestedStartTime(slotA);
        bookingA.setRequestedEndTime(slotA.plus(1, ChronoUnit.HOURS));
        Booking bookingB = new Booking();
        bookingB.setId(UUID.randomUUID());
        bookingB.setStatus("REQUESTED");
        bookingB.setRequestedStartTime(slotB);
        bookingB.setRequestedEndTime(slotB.plus(1, ChronoUnit.HOURS));
        when(bookingRepository.findByBatchIdAndStatus(BATCH_ID, "REQUESTED"))
            .thenReturn(List.of(bookingA, bookingB));

        when(bookingRepository.findOverlappingBookings(eq(COACH_ID), eq(slotA), any(), any(), any()))
            .thenReturn(List.of());
        when(bookingRepository.findOverlappingBookings(eq(COACH_ID), eq(slotB), any(), any(), any()))
            .thenReturn(List.of(bookingInStatus("CONFIRMED")));

        when(bookingRepository.findByBatchId(BATCH_ID))
            .thenReturn(List.of(bookingInStatus("PAYMENT_PENDING"), bookingB));
        when(batchRepository.save(any())).thenReturn(batch);
        when(userRepository.findById(any())).thenReturn(Optional.empty());

        List<BatchAcceptResult> results = service.acceptAll(BATCH_ID, COACH_USER_ID);

        assertThat(results).hasSize(2);
        assertThat(results).anySatisfy(r -> {
            assertThat(r.bookingId()).isEqualTo(bookingA.getId());
            assertThat(r.accepted()).isTrue();
            assertThat(r.errorKey()).isNull();
        });
        assertThat(results).anySatisfy(r -> {
            assertThat(r.bookingId()).isEqualTo(bookingB.getId());
            assertThat(r.accepted()).isFalse();
            assertThat(r.errorKey()).isEqualTo("booking.slotUnavailable");
        });
    }

    /**
     * resolveFailureCode's Javadoc states its ResponseStatusException case (the corrupted-status
     * throw site, BookingService.readStatusOrThrow) must NEVER be special-cased to leak
     * getReason() into the wire errorKey, and must fall through to "generic.unknown" like any
     * other unmapped exception. This test pins that fallback with an executable assertion, per
     * the skillars-deferred-34 code review's Blind Hunter finding that the invariant was
     * previously enforced only by a code comment.
     */
    @Test
    void acceptAll_oneBookingHasCorruptedStatus_returnsGenericUnknownNotRawMessage() {
        BookingBatch batch = new BookingBatch();
        batch.setId(BATCH_ID);
        batch.setCoachId(COACH_ID);
        batch.setParentId(PARENT_ID);
        batch.setStatus("PENDING");
        batch.setTotalAmount(BigDecimal.ZERO);
        when(batchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));
        // skillars-deferred-69 AC6: acceptAll's trailing transaction now takes the locked read.
        when(batchRepository.findByIdForUpdate(BATCH_ID)).thenReturn(Optional.of(batch));

        CoachProfile coach = buildActiveCoach();
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));

        // A second, unrelated booking that succeeds — acceptAll's acceptedIds.isEmpty() branch
        // throws BATCH_NONE_ACCEPTED instead of returning results when every booking fails, which
        // would defeat this test's own point; this mirrors the existing one-succeeds-one-fails
        // fixture shape used by acceptAll_oneSlotCollides_returnsOneAcceptedOneFailedResult.
        Instant okSlot = Instant.now().plus(4, ChronoUnit.DAYS);
        Booking ok = new Booking();
        ok.setId(UUID.randomUUID());
        ok.setStatus("REQUESTED");
        ok.setRequestedStartTime(okSlot);
        ok.setRequestedEndTime(okSlot.plus(1, ChronoUnit.HOURS));

        Instant corruptedSlot = Instant.now().plus(3, ChronoUnit.DAYS);
        Booking corrupted = new Booking();
        corrupted.setId(UUID.randomUUID());
        corrupted.setStatus("REQUESTED");
        corrupted.setRequestedStartTime(corruptedSlot);
        corrupted.setRequestedEndTime(corruptedSlot.plus(1, ChronoUnit.HOURS));
        when(bookingRepository.findByBatchIdAndStatus(BATCH_ID, "REQUESTED"))
            .thenReturn(List.of(ok, corrupted));

        when(bookingRepository.findOverlappingBookings(eq(COACH_ID), any(), any(), any(), any()))
            .thenReturn(List.of());

        doNothing().when(bookingService).acceptAndInitiatePayment(eq(ok.getId()), any());
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT,
            "Booking " + corrupted.getId() + " has unrecognised status 'FOOBAR'"))
            .when(bookingService).acceptAndInitiatePayment(eq(corrupted.getId()), any());

        when(bookingRepository.findByBatchId(BATCH_ID)).thenReturn(List.of(ok, corrupted));
        when(batchRepository.save(any())).thenReturn(batch);
        when(userRepository.findById(any())).thenReturn(Optional.empty());

        List<BatchAcceptResult> results = service.acceptAll(BATCH_ID, COACH_USER_ID);

        assertThat(results).hasSize(2);
        assertThat(results).anySatisfy(r -> {
            assertThat(r.bookingId()).isEqualTo(ok.getId());
            assertThat(r.accepted()).isTrue();
        });
        assertThat(results).anySatisfy(r -> {
            assertThat(r.bookingId()).isEqualTo(corrupted.getId());
            assertThat(r.accepted()).isFalse();
            assertThat(r.errorKey())
                .as("must fall back to the stable generic code, never the raw ResponseStatusException reason")
                .isEqualTo("generic.unknown")
                .doesNotContain(corrupted.getId().toString())
                .doesNotContain("FOOBAR");
        });
    }

    @Test
    void acceptAll_oneBookingRacesConcurrentModification_returnsConcurrentModificationCode() {
        BookingBatch batch = new BookingBatch();
        batch.setId(BATCH_ID);
        batch.setCoachId(COACH_ID);
        batch.setParentId(PARENT_ID);
        batch.setStatus("PENDING");
        batch.setTotalAmount(BigDecimal.ZERO);
        when(batchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));
        // skillars-deferred-69 AC6: acceptAll's trailing transaction now takes the locked read.
        when(batchRepository.findByIdForUpdate(BATCH_ID)).thenReturn(Optional.of(batch));

        CoachProfile coach = buildActiveCoach();
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));

        Instant okSlot = Instant.now().plus(4, ChronoUnit.DAYS);
        Booking ok = new Booking();
        ok.setId(UUID.randomUUID());
        ok.setStatus("REQUESTED");
        ok.setRequestedStartTime(okSlot);
        ok.setRequestedEndTime(okSlot.plus(1, ChronoUnit.HOURS));

        Instant racedSlot = Instant.now().plus(3, ChronoUnit.DAYS);
        Booking raced = new Booking();
        raced.setId(UUID.randomUUID());
        raced.setStatus("REQUESTED");
        raced.setRequestedStartTime(racedSlot);
        raced.setRequestedEndTime(racedSlot.plus(1, ChronoUnit.HOURS));
        when(bookingRepository.findByBatchIdAndStatus(BATCH_ID, "REQUESTED"))
            .thenReturn(List.of(ok, raced));

        when(bookingRepository.findOverlappingBookings(eq(COACH_ID), any(), any(), any(), any()))
            .thenReturn(List.of());

        doNothing().when(bookingService).acceptAndInitiatePayment(eq(ok.getId()), any());
        doThrow(new OptimisticLockingFailureException("test"))
            .when(bookingService).acceptAndInitiatePayment(eq(raced.getId()), any());

        when(bookingRepository.findByBatchId(BATCH_ID)).thenReturn(List.of(ok, raced));
        when(batchRepository.save(any())).thenReturn(batch);
        when(userRepository.findById(any())).thenReturn(Optional.empty());

        List<BatchAcceptResult> results = service.acceptAll(BATCH_ID, COACH_USER_ID);

        assertThat(results).hasSize(2);
        assertThat(results).anySatisfy(r -> {
            assertThat(r.bookingId()).isEqualTo(raced.getId());
            assertThat(r.accepted()).isFalse();
            assertThat(r.errorKey()).isEqualTo(BookingError.CONCURRENT_MODIFICATION.getErrorCode());
        });
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

    /**
     * Deferred-31 AC2, path (a): every per-booking accept threw and was swallowed by the loop's
     * catch. acceptAll used to `return` here, which is an HTTP 2xx, so the coach read
     * "All sessions accepted" over nothing accepted and the batch stayed PENDING.
     */
    @Test
    void acceptAll_everyBookingFailsToAccept_throwsBatchNoneAcceptedAndLeavesBatchPending() {
        BookingBatch batch = new BookingBatch();
        batch.setId(BATCH_ID);
        batch.setCoachId(COACH_ID);
        batch.setParentId(PARENT_ID);
        batch.setStatus("PENDING");
        batch.setTotalAmount(BigDecimal.ZERO);
        when(batchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));

        CoachProfile coach = buildActiveCoach();
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));

        Instant slot = Instant.now().plus(3, ChronoUnit.DAYS);
        Booking requested = new Booking();
        requested.setId(UUID.randomUUID());
        requested.setStatus("REQUESTED");
        requested.setRequestedStartTime(slot);
        requested.setRequestedEndTime(slot.plus(1, ChronoUnit.HOURS));
        when(bookingRepository.findByBatchIdAndStatus(BATCH_ID, "REQUESTED")).thenReturn(List.of(requested));
        // Every per-booking accept dies on the slot-collision guard inside acceptOneBooking, and the
        // loop's catch swallows it — acceptedIds ends up empty. Never reaches acceptAll's trailing
        // transaction, so no findByIdForUpdate stub is needed here (skillars-deferred-69 AC6).
        when(bookingRepository.findOverlappingBookings(any(), any(), any(), any(), any()))
            .thenReturn(List.of(bookingInStatus("CONFIRMED")));

        assertThatThrownBy(() -> service.acceptAll(BATCH_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> {
                OperationNotAllowedException onae = (OperationNotAllowedException) e;
                assertThat(onae.getErrorCode()).isEqualTo(BookingError.BATCH_NONE_ACCEPTED);
                assertThat(onae.getLogContext())
                    .containsEntry("batch id", BATCH_ID)
                    .containsEntry("per-booking results",
                        List.of(new BatchAcceptResult(requested.getId(), false, "booking.slotUnavailable")));
            });

        verify(batchRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(BatchBookingAcceptedEvent.class));
        assertThat(batch.getStatus())
            .as("nothing was accepted, so the batch must stay PENDING")
            .isEqualTo("PENDING");
    }

    /**
     * Deferred-31 AC2, path (b): the batch is still PENDING but findByBatchIdAndStatus returns no
     * REQUESTED bookings at all, so the loop never runs. Same false-success symptom, different cause
     * — both must reach the same 403.
     */
    @Test
    void acceptAll_pendingBatchWithNoRequestedBookings_throwsBatchNoneAccepted() {
        BookingBatch batch = new BookingBatch();
        batch.setId(BATCH_ID);
        batch.setCoachId(COACH_ID);
        batch.setParentId(PARENT_ID);
        batch.setStatus("PENDING");
        batch.setTotalAmount(BigDecimal.ZERO);
        when(batchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(buildActiveCoach()));
        when(bookingRepository.findByBatchIdAndStatus(BATCH_ID, "REQUESTED")).thenReturn(List.of());

        assertThatThrownBy(() -> service.acceptAll(BATCH_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> {
                OperationNotAllowedException onae = (OperationNotAllowedException) e;
                assertThat(onae.getErrorCode()).isEqualTo(BookingError.BATCH_NONE_ACCEPTED);
                assertThat(onae.getLogContext())
                    .containsEntry("batch id", BATCH_ID)
                    .containsEntry("per-booking results", List.of());
            });

        verify(bookingService, never()).acceptAndInitiatePayment(any(), any());
        verify(batchRepository, never()).save(any());
        assertThat(batch.getStatus()).isEqualTo("PENDING");
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
        return new CreateBatchRequest(COACH_ID, PLAYER_ID, slots, BigDecimal.ZERO, null);
    }
}
