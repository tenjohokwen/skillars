package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.BatchRuleViolationException;
import com.softropic.skillars.platform.booking.contract.PackPausedEvent;
import com.softropic.skillars.platform.booking.contract.PauseConflictResponse;
import com.softropic.skillars.platform.booking.contract.PausePackRequest;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchase;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PackSessionServicePauseTest {

    @Mock SessionPackPurchaseRepository sessionPackPurchaseRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock BookingRepository bookingRepository;
    @Mock BookingService bookingService;
    @Mock ConfigService configService;
    @Mock CoachProfileRepository coachProfileRepository;
    @Mock UserRepository userRepository;
    @Mock PessimisticLockRetryer lockRetryer;
    @Mock Clock clock;

    @InjectMocks PackSessionService packSessionService;

    @BeforeEach
    void setUpCollaborators() {
        lenient().when(lockRetryer.withBoundedRetry(any()))
            .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get());
        // skillars-deferred-103 AC5: pausePack resolves today via clock.withZone(zone). Return a
        // real system clock in the requested zone so relative pauseStart values stay meaningful.
        lenient().when(clock.withZone(any(ZoneId.class)))
            .thenAnswer(inv -> Clock.system(inv.getArgument(0)));
        lenient().when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        lenient().when(clock.instant()).thenReturn(Instant.now());
    }

    private static final Long PARENT_ID = 9001L;
    private static final Long PLAYER_ID = 9002L;
    private static final UUID COACH_ID = UUID.randomUUID();
    private static final UUID PURCHASE_ID = UUID.randomUUID();

    private CoachProfile coachProfile(String timezone) {
        CoachProfile coach = new CoachProfile();
        coach.setDisplayName("Test Coach");
        coach.setCanonicalTimezone(timezone);
        return coach;
    }

    @Test
    void pausePack_noConflicts_appliesPauseAndPublishesEvent() {
        SessionPackPurchase purchase = buildPurchase();
        when(sessionPackPurchaseRepository.findByIdForUpdate(PURCHASE_ID)).thenReturn(Optional.of(purchase));
        when(configService.getLong(eq("pack.pause.maxDays"), anyLong())).thenReturn(90L);
        when(bookingRepository.findConflictingBookingsForPause(any(), any(), any(), any(), anyList()))
            .thenReturn(List.of());
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coachProfile("UTC")));
        User parentUser = mock(User.class);
        when(parentUser.getEmail()).thenReturn("parent@test.com");
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(parentUser));

        Instant pauseStart = Instant.now().plus(5, ChronoUnit.DAYS);
        PausePackRequest req = new PausePackRequest(pauseStart, 14, List.of());

        PauseConflictResponse response = packSessionService.pausePack(PARENT_ID, PURCHASE_ID, req);

        assertThat(response.pauseApplied()).isTrue();
        assertThat(response.conflictingBookings()).isEmpty();
        assertThat(purchase.getPausedUntil()).isEqualTo(pauseStart.plus(14, ChronoUnit.DAYS));
        verify(sessionPackPurchaseRepository).save(purchase);

        ArgumentCaptor<PackPausedEvent> captor = ArgumentCaptor.forClass(PackPausedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getPackId()).isEqualTo(PURCHASE_ID);
        assertThat(captor.getValue().getCoachDisplayName()).isEqualTo("Test Coach");
    }

    @Test
    void pausePack_alreadyPaused_throwsBatchRuleViolation() {
        SessionPackPurchase purchase = buildPurchase();
        purchase.setPausedUntil(Instant.now().plus(10, ChronoUnit.DAYS));
        when(sessionPackPurchaseRepository.findByIdForUpdate(PURCHASE_ID)).thenReturn(Optional.of(purchase));

        PausePackRequest req = new PausePackRequest(Instant.now().plus(1, ChronoUnit.DAYS), 14, List.of());

        assertThatThrownBy(() -> packSessionService.pausePack(PARENT_ID, PURCHASE_ID, req))
            .isInstanceOf(BatchRuleViolationException.class)
            .hasMessageContaining("booking.packAlreadyPaused");

        verify(sessionPackPurchaseRepository, never()).save(any());
    }

    @Test
    void pausePack_wrongParent_throwsOperationNotAllowed() {
        SessionPackPurchase purchase = buildPurchase();
        when(sessionPackPurchaseRepository.findByIdForUpdate(PURCHASE_ID)).thenReturn(Optional.of(purchase));

        PausePackRequest req = new PausePackRequest(Instant.now().plus(1, ChronoUnit.DAYS), 14, List.of());

        assertThatThrownBy(() -> packSessionService.pausePack(PARENT_ID + 1, PURCHASE_ID, req))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void pausePack_conflictWithoutConfirmation_returnsConflictsWithoutApplying() {
        SessionPackPurchase purchase = buildPurchase();
        when(sessionPackPurchaseRepository.findByIdForUpdate(PURCHASE_ID)).thenReturn(Optional.of(purchase));
        when(configService.getLong(eq("pack.pause.maxDays"), anyLong())).thenReturn(90L);
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coachProfile("UTC")));

        Booking conflictingBooking = mock(Booking.class);
        UUID bookingId = UUID.randomUUID();
        when(conflictingBooking.getId()).thenReturn(bookingId);
        when(conflictingBooking.getRequestedStartTime()).thenReturn(Instant.now().plus(6, ChronoUnit.DAYS));
        when(conflictingBooking.getRequestedEndTime()).thenReturn(Instant.now().plus(6, ChronoUnit.DAYS).plusSeconds(3600));
        when(conflictingBooking.getStatus()).thenReturn("CONFIRMED");
        when(conflictingBooking.getCanonicalTimezone()).thenReturn("UTC");
        when(bookingRepository.findConflictingBookingsForPause(any(), any(), any(), any(), anyList()))
            .thenReturn(List.of(conflictingBooking));

        Instant pauseStart = Instant.now().plus(5, ChronoUnit.DAYS);
        PausePackRequest req = new PausePackRequest(pauseStart, 14, List.of());

        PauseConflictResponse response = packSessionService.pausePack(PARENT_ID, PURCHASE_ID, req);

        assertThat(response.pauseApplied()).isFalse();
        assertThat(response.conflictingBookings()).hasSize(1);
        assertThat(response.conflictingBookings().get(0).id()).isEqualTo(bookingId);
        assertThat(purchase.getPausedUntil()).isNull();
        verify(sessionPackPurchaseRepository, never()).save(any());
        verify(bookingService, never()).cancelDueToPause(any(), any(), any());
    }

    @Test
    void pausePack_confirmedConflict_cancelsBookingAndAppliesPause() {
        SessionPackPurchase purchase = buildPurchase();
        when(sessionPackPurchaseRepository.findByIdForUpdate(PURCHASE_ID)).thenReturn(Optional.of(purchase));
        when(configService.getLong(eq("pack.pause.maxDays"), anyLong())).thenReturn(90L);
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coachProfile("UTC")));

        Booking conflictingBooking = mock(Booking.class);
        UUID bookingId = UUID.randomUUID();
        when(conflictingBooking.getId()).thenReturn(bookingId);
        when(conflictingBooking.getRequestedStartTime()).thenReturn(Instant.now().plus(6, ChronoUnit.DAYS));
        when(bookingRepository.findConflictingBookingsForPause(any(), any(), any(), any(), anyList()))
            .thenReturn(List.of(conflictingBooking));
        User parentUser = mock(User.class);
        when(parentUser.getEmail()).thenReturn("parent@test.com");
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(parentUser));

        Instant pauseStart = Instant.now().plus(5, ChronoUnit.DAYS);
        PausePackRequest req = new PausePackRequest(pauseStart, 14, List.of(bookingId));

        PauseConflictResponse response = packSessionService.pausePack(PARENT_ID, PURCHASE_ID, req);

        assertThat(response.pauseApplied()).isTrue();
        verify(bookingService).cancelDueToPause(bookingId, COACH_ID, PARENT_ID);
        assertThat(purchase.getPausedUntil()).isEqualTo(pauseStart.plus(14, ChronoUnit.DAYS));
        verify(sessionPackPurchaseRepository).save(purchase);
    }

    // ---- skillars-deferred-103 AC5 / AC6 / AC7 ------------------------------------------------

    @Test
    void pausePack_missingCoach_throwsIllegalStateException_notMissingRights() {
        SessionPackPurchase purchase = buildPurchase();
        when(sessionPackPurchaseRepository.findByIdForUpdate(PURCHASE_ID)).thenReturn(Optional.of(purchase));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.empty());

        PausePackRequest req = new PausePackRequest(Instant.now().plus(5, ChronoUnit.DAYS), 14, List.of());

        // AC7 / P11: an FK-backed coach row that has vanished is a data-integrity failure (500),
        // never a 403 MISSING_RIGHTS.
        assertThatThrownBy(() -> packSessionService.pausePack(PARENT_ID, PURCHASE_ID, req))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("data-integrity");
        verify(sessionPackPurchaseRepository, never()).save(any());
    }

    @Test
    void pausePack_blankCoachTimezone_fallsBackToUtcAndStillApplies() {
        SessionPackPurchase purchase = buildPurchase();
        when(sessionPackPurchaseRepository.findByIdForUpdate(PURCHASE_ID)).thenReturn(Optional.of(purchase));
        when(configService.getLong(eq("pack.pause.maxDays"), anyLong())).thenReturn(90L);
        when(bookingRepository.findConflictingBookingsForPause(any(), any(), any(), any(), anyList()))
            .thenReturn(List.of());
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coachProfile("")));
        User parentUser = mock(User.class);
        when(parentUser.getEmail()).thenReturn("parent@test.com");
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(parentUser));

        Instant pauseStart = Instant.now().plus(5, ChronoUnit.DAYS);
        PauseConflictResponse response =
            packSessionService.pausePack(PARENT_ID, PURCHASE_ID, new PausePackRequest(pauseStart, 14, List.of()));

        assertThat(response.pauseApplied()).isTrue();
        verify(eventPublisher).publishEvent(any(PackPausedEvent.class));
    }

    @Test
    void pausePack_unrecognisedCoachTimezone_fallsBackToUtc_doesNotThrow() {
        SessionPackPurchase purchase = buildPurchase();
        when(sessionPackPurchaseRepository.findByIdForUpdate(PURCHASE_ID)).thenReturn(Optional.of(purchase));
        when(configService.getLong(eq("pack.pause.maxDays"), anyLong())).thenReturn(90L);
        when(bookingRepository.findConflictingBookingsForPause(any(), any(), any(), any(), anyList()))
            .thenReturn(List.of());
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coachProfile("Europe/Nowhere")));
        User parentUser = mock(User.class);
        when(parentUser.getEmail()).thenReturn("parent@test.com");
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(parentUser));

        Instant pauseStart = Instant.now().plus(5, ChronoUnit.DAYS);
        PauseConflictResponse response =
            packSessionService.pausePack(PARENT_ID, PURCHASE_ID, new PausePackRequest(pauseStart, 14, List.of()));

        assertThat(response.pauseApplied()).isTrue();
    }

    @Test
    void pausePack_pauseStartInThePast_throwsBatchRuleViolation() {
        SessionPackPurchase purchase = buildPurchase();
        when(sessionPackPurchaseRepository.findByIdForUpdate(PURCHASE_ID)).thenReturn(Optional.of(purchase));
        when(configService.getLong(eq("pack.pause.maxDays"), anyLong())).thenReturn(90L);
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coachProfile("UTC")));

        Instant pauseStart = Instant.now().minus(2, ChronoUnit.DAYS);
        PausePackRequest req = new PausePackRequest(pauseStart, 14, List.of());

        assertThatThrownBy(() -> packSessionService.pausePack(PARENT_ID, PURCHASE_ID, req))
            .isInstanceOf(BatchRuleViolationException.class)
            .hasMessageContaining("booking.pauseStartInPast");
        verify(sessionPackPurchaseRepository, never()).save(any());
    }

    @Test
    void pausePack_durationExceedsConfiguredMax_throwsBatchRuleViolation() {
        SessionPackPurchase purchase = buildPurchase();
        when(sessionPackPurchaseRepository.findByIdForUpdate(PURCHASE_ID)).thenReturn(Optional.of(purchase));
        when(configService.getLong(eq("pack.pause.maxDays"), anyLong())).thenReturn(30L);
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coachProfile("UTC")));

        PausePackRequest req = new PausePackRequest(Instant.now().plus(5, ChronoUnit.DAYS), 45, List.of());

        assertThatThrownBy(() -> packSessionService.pausePack(PARENT_ID, PURCHASE_ID, req))
            .isInstanceOf(BatchRuleViolationException.class)
            .hasMessageContaining("booking.pauseDurationInvalid");
        // AC6: the defensive-default overload is the one being called.
        verify(configService).getLong(eq("pack.pause.maxDays"), anyLong());
    }

    @Test
    void pausePack_blankParentEmail_appliesPauseButSkipsNotification() {
        SessionPackPurchase purchase = buildPurchase();
        when(sessionPackPurchaseRepository.findByIdForUpdate(PURCHASE_ID)).thenReturn(Optional.of(purchase));
        when(configService.getLong(eq("pack.pause.maxDays"), anyLong())).thenReturn(90L);
        when(bookingRepository.findConflictingBookingsForPause(any(), any(), any(), any(), anyList()))
            .thenReturn(List.of());
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coachProfile("UTC")));
        User parentUser = mock(User.class);
        when(parentUser.getEmail()).thenReturn("   ");
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(parentUser));

        Instant pauseStart = Instant.now().plus(5, ChronoUnit.DAYS);
        PauseConflictResponse response =
            packSessionService.pausePack(PARENT_ID, PURCHASE_ID, new PausePackRequest(pauseStart, 14, List.of()));

        assertThat(response.pauseApplied()).isTrue();
        verify(sessionPackPurchaseRepository).save(purchase);
        verify(eventPublisher, never()).publishEvent(any(PackPausedEvent.class));
    }

    private SessionPackPurchase buildPurchase() {
        SessionPackPurchase purchase = new SessionPackPurchase();
        purchase.setPurchaseId(PURCHASE_ID);
        purchase.setParentId(PARENT_ID);
        purchase.setPlayerId(PLAYER_ID);
        purchase.setCoachId(COACH_ID);
        purchase.setRemainingSessions(5);
        purchase.setExpiresAt(Instant.now().plus(60, ChronoUnit.DAYS));
        return purchase;
    }
}
