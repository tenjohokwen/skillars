package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.contract.event.RelatedPartyBookingFlaggedEvent;
import com.softropic.skillars.platform.payment.repo.RelatedPartyBookingRepository;
import com.softropic.skillars.platform.security.contract.IdentitySignalKind;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import com.softropic.skillars.platform.security.service.UserIdentitySignalService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * feat/security-s06-related-party: the decision rule of {@link RelatedPartyService} — which booking is flagged, that
 * only the payer and the booked coach are compared, idempotency, and the hold it hands the payout.
 */
@ExtendWith(MockitoExtension.class)
class RelatedPartyServiceTest {

    private static final long PAYER = 11L;
    private static final long COACH_USER = 22L;
    private static final long PLAYER_USER = 33L;
    private static final long PLAYER_ID = 44L;

    @Mock RelatedPartyBookingRepository relatedPartyBookingRepository;
    @Mock BookingRepository bookingRepository;
    @Mock CoachProfileRepository coachProfileRepository;
    @Mock PlayerProfileRepository playerProfileRepository;
    @Mock UserRepository userRepository;
    @Mock UserIdentitySignalService signalService;
    @Mock ConfigService configService;
    @Mock ApplicationEventPublisher eventPublisher;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final UUID bookingId = UUID.randomUUID();
    private final UUID coachId = UUID.randomUUID();
    private RelatedPartyService service;

    @BeforeEach
    void setUp() {
        service = new RelatedPartyService(relatedPartyBookingRepository, bookingRepository, coachProfileRepository,
            playerProfileRepository, userRepository, signalService, configService, eventPublisher, meterRegistry);
        Booking booking = new Booking();
        booking.setId(bookingId);
        booking.setParentId(PAYER);
        booking.setCoachId(coachId);
        booking.setPlayerId(PLAYER_ID);
        lenient().when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        CoachProfile coach = new CoachProfile();
        coach.setUserId(COACH_USER);
        lenient().when(coachProfileRepository.findById(coachId)).thenReturn(Optional.of(coach));
        PlayerProfile player = new PlayerProfile();
        player.setUserId(PLAYER_USER);
        lenient().when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        lenient().when(userRepository.findOneById(anyLong())).thenReturn(Optional.of(new User()));
        lenient().when(configService.getBoundedLong(ConfigBounds.PAYOUT_RELATED_PARTY_HOLD_DAYS)).thenReturn(60L);
    }

    @Test
    void sharedCardFingerprint_flagsTheBooking_andPublishesTheEventOnce() {
        when(signalService.sharedKinds(PAYER, COACH_USER)).thenReturn(EnumSet.of(IdentitySignalKind.CARD));
        when(relatedPartyBookingRepository.insertIfAbsent(eq(bookingId), eq(coachId), eq(PAYER), eq(PLAYER_USER),
            eq("CARD"), any(Instant.class))).thenReturn(1);

        assertThat(service.evaluate(bookingId, RelatedPartyService.TRIGGER_CONFIRMATION)).isTrue();

        ArgumentCaptor<RelatedPartyBookingFlaggedEvent> event = ArgumentCaptor.forClass(RelatedPartyBookingFlaggedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue()).isEqualTo(new RelatedPartyBookingFlaggedEvent(bookingId, coachId, PAYER, PLAYER_USER));
        assertThat(meterRegistry.counter(RelatedPartyService.METRIC_FLAGGED, "trigger", "confirmation").count())
            .isEqualTo(1.0);
    }

    @Test
    void everySignalKindFlags_andTheMatchedKindsAreStoredSorted() {
        when(signalService.sharedKinds(PAYER, COACH_USER))
            .thenReturn(EnumSet.of(IdentitySignalKind.PHONE, IdentitySignalKind.BROWSER, IdentitySignalKind.EMAIL));
        when(relatedPartyBookingRepository.insertIfAbsent(any(), any(), anyLong(), any(), anyString(), any()))
            .thenReturn(1);

        assertThat(service.evaluate(bookingId, RelatedPartyService.TRIGGER_PAYOUT)).isTrue();

        verify(relatedPartyBookingRepository).insertIfAbsent(eq(bookingId), eq(coachId), eq(PAYER), eq(PLAYER_USER),
            eq("BROWSER,EMAIL,PHONE"), any(Instant.class));
    }

    @Test
    void noSharedSignal_isNotFlagged_soACoachWhoIsAParentElsewhereIsUntouched() {
        when(signalService.sharedKinds(PAYER, COACH_USER)).thenReturn(EnumSet.noneOf(IdentitySignalKind.class));

        assertThat(service.evaluate(bookingId, RelatedPartyService.TRIGGER_CONFIRMATION)).isFalse();

        verify(relatedPartyBookingRepository, never()).insertIfAbsent(any(), any(), anyLong(), any(), anyString(), any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void onlyThePayerAndTheBookedCoachAreCompared() {
        when(signalService.sharedKinds(PAYER, COACH_USER)).thenReturn(EnumSet.noneOf(IdentitySignalKind.class));

        service.evaluate(bookingId, RelatedPartyService.TRIGGER_CONFIRMATION);

        verify(signalService).sharedKinds(PAYER, COACH_USER);
        verify(signalService, never()).sharedKinds(eq(PLAYER_USER), anyLong());
    }

    @Test
    void payerIsTheCoachsOwnUser_isFlaggedWithoutAnySignalLookup() {
        CoachProfile self = new CoachProfile();
        self.setUserId(PAYER);
        when(coachProfileRepository.findById(coachId)).thenReturn(Optional.of(self));
        when(relatedPartyBookingRepository.insertIfAbsent(any(), any(), anyLong(), any(), anyString(), any()))
            .thenReturn(1);

        assertThat(service.evaluate(bookingId, RelatedPartyService.TRIGGER_CONFIRMATION)).isTrue();

        verify(relatedPartyBookingRepository).insertIfAbsent(eq(bookingId), eq(coachId), eq(PAYER), eq(PLAYER_USER),
            eq("SELF"), any(Instant.class));
        verify(signalService, never()).sharedKinds(anyLong(), anyLong());
    }

    @Test
    void alreadyFlagged_isIdempotent_noReevaluationNoSecondEvent() {
        when(relatedPartyBookingRepository.existsById(bookingId)).thenReturn(true);

        assertThat(service.evaluate(bookingId, RelatedPartyService.TRIGGER_PAYOUT)).isTrue();

        verify(bookingRepository, never()).findById(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void concurrentFlagger_whoseInsertLost_publishesNoEvent() {
        when(signalService.sharedKinds(PAYER, COACH_USER)).thenReturn(EnumSet.of(IdentitySignalKind.EMAIL));
        when(relatedPartyBookingRepository.insertIfAbsent(any(), any(), anyLong(), any(), anyString(), any()))
            .thenReturn(0);

        assertThat(service.evaluate(bookingId, RelatedPartyService.TRIGGER_PAYOUT)).isTrue();

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void holdPayoutUntil_flagged_isHoldDaysFromNow_andExtendsTheStoredGate() {
        when(relatedPartyBookingRepository.existsById(bookingId)).thenReturn(true);
        when(configService.getBoundedLong(ConfigBounds.PAYOUT_RELATED_PARTY_HOLD_DAYS)).thenReturn(45L);
        Instant before = Instant.now();

        Instant until = service.holdPayoutUntil(bookingId);

        assertThat(until).isBetween(before.plus(Duration.ofDays(45)), Instant.now().plus(Duration.ofDays(45)));
        verify(relatedPartyBookingRepository).extendHold(bookingId, until);
    }

    @Test
    void holdPayoutUntil_notFlagged_isNull() {
        when(signalService.sharedKinds(PAYER, COACH_USER)).thenReturn(EnumSet.noneOf(IdentitySignalKind.class));

        assertThat(service.holdPayoutUntil(bookingId)).isNull();
        verify(relatedPartyBookingRepository, never()).extendHold(any(), any());
    }

    @Test
    void holdPayoutUntil_evaluationError_neverBlocksThePayout() {
        when(relatedPartyBookingRepository.existsById(bookingId)).thenThrow(new IllegalStateException("db down"));

        assertThat(service.holdPayoutUntil(bookingId)).isNull();
    }

    @Test
    void isRelatedPartyAuthor_delegatesAndIgnoresNulls() {
        when(relatedPartyBookingRepository.existsForCoachAndUser(coachId, PAYER)).thenReturn(true);

        assertThat(service.isRelatedPartyAuthor(coachId, PAYER)).isTrue();
        assertThat(service.isRelatedPartyAuthor(null, PAYER)).isFalse();
        assertThat(service.isRelatedPartyAuthor(coachId, null)).isFalse();
    }
}
