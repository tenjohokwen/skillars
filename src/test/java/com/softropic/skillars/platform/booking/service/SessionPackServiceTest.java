package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.PaymentGateway;
import com.softropic.skillars.platform.booking.contract.SessionPackExhaustedEvent;
import com.softropic.skillars.platform.booking.contract.SessionPackMapper;
import com.softropic.skillars.platform.booking.contract.SessionPackPurchasedResponse;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.repo.SessionPackPurchased;
import com.softropic.skillars.platform.booking.repo.SessionPackPurchasedRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachPricing;
import com.softropic.skillars.platform.marketplace.repo.CoachPricingRepository;
import com.softropic.skillars.platform.marketplace.repo.SessionPack;
import com.softropic.skillars.platform.marketplace.repo.SessionPackRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionPackServiceTest {

    @Mock SessionPackPurchasedRepository repository;
    @Mock SessionPackRepository sessionPackRepository;
    @Mock CoachProfileRepository coachProfileRepository;
    @Mock CoachPricingRepository coachPricingRepository;
    @Mock PlayerProfileRepository playerProfileRepository;
    @Mock PaymentGateway paymentGateway;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock SessionPackMapper mapper;
    @Mock BookingRepository bookingRepository;

    @InjectMocks SessionPackService service;

    private static final Long PLAYER_ID = 1001L;
    private static final Long PARENT_ID = 2001L;
    private static final UUID COACH_ID  = UUID.randomUUID();

    @Test
    void deductCredit_singleActivePack_decrementsCredits() {
        SessionPackPurchased pack = packWith(PLAYER_ID, COACH_ID, 5, 3);
        when(repository.findActivePacksForDeduction(PLAYER_ID, COACH_ID)).thenReturn(List.of(pack));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.deductCredit(PLAYER_ID, COACH_ID);

        assertThat(pack.getCreditsRemaining()).isEqualTo(2);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void deductCredit_fifo_oldestPackConsumedFirst() {
        Instant older = Instant.parse("2026-01-01T00:00:00Z");
        Instant newer = Instant.parse("2026-03-01T00:00:00Z");

        SessionPackPurchased oldPack = packWith(PLAYER_ID, COACH_ID, 3, 3);
        oldPack.setPurchasedAt(older);

        SessionPackPurchased newPack = packWith(PLAYER_ID, COACH_ID, 5, 5);
        newPack.setPurchasedAt(newer);

        // repository returns FIFO order (oldest first)
        when(repository.findActivePacksForDeduction(PLAYER_ID, COACH_ID)).thenReturn(List.of(oldPack, newPack));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.deductCredit(PLAYER_ID, COACH_ID);

        assertThat(oldPack.getCreditsRemaining()).isEqualTo(2);
        assertThat(newPack.getCreditsRemaining()).isEqualTo(5);
    }

    @Test
    void deductCredit_packExhausted_statusChangesToExhaustedAndEventPublished() {
        SessionPackPurchased pack = packWith(PLAYER_ID, COACH_ID, 3, 1);
        when(repository.findActivePacksForDeduction(PLAYER_ID, COACH_ID)).thenReturn(List.of(pack));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.deductCredit(PLAYER_ID, COACH_ID);

        assertThat(pack.getCreditsRemaining()).isEqualTo(0);
        assertThat(pack.getStatus()).isEqualTo("EXHAUSTED");

        ArgumentCaptor<SessionPackExhaustedEvent> captor = ArgumentCaptor.forClass(SessionPackExhaustedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getPlayerId()).isEqualTo(PLAYER_ID);
        assertThat(captor.getValue().getCoachId()).isEqualTo(COACH_ID);
    }

    @Test
    void deductCredit_noActiveCredits_throwsOperationNotAllowedException() {
        when(repository.findActivePacksForDeduction(PLAYER_ID, COACH_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.deductCredit(PLAYER_ID, COACH_ID))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void hasCredits_activePackExists_returnsTrue() {
        when(repository.sumActiveCredits(PLAYER_ID, COACH_ID)).thenReturn(3);
        when(bookingRepository.countInFlightBookings(PLAYER_ID, COACH_ID)).thenReturn(0L);

        assertThat(service.hasCredits(PLAYER_ID, COACH_ID)).isTrue();
    }

    @Test
    void hasCredits_allPacksExhausted_returnsFalse() {
        when(repository.sumActiveCredits(PLAYER_ID, COACH_ID)).thenReturn(0);
        when(bookingRepository.countInFlightBookings(PLAYER_ID, COACH_ID)).thenReturn(0L);

        assertThat(service.hasCredits(PLAYER_ID, COACH_ID)).isFalse();
    }

    @Test
    void purchasePack_validRequest_createsRecordWithCorrectCredits() {
        UUID packId = UUID.randomUUID();

        PlayerProfile player = new PlayerProfile();
        player.setParentId(PARENT_ID);

        SessionPack offered = new SessionPack();
        offered.setCoachId(COACH_ID);
        offered.setSessionCount(5);
        offered.setTotalPrice(new BigDecimal("100.00"));

        CoachProfile coachProfile = new CoachProfile();
        coachProfile.setDisplayName("Test Coach");

        CoachPricing pricing = new CoachPricing();
        pricing.setCurrency("EUR");

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(sessionPackRepository.findById(packId)).thenReturn(Optional.of(offered));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coachProfile));
        when(coachPricingRepository.findByCoachId(COACH_ID)).thenReturn(Optional.of(pricing));
        when(paymentGateway.capturePayment(any(), any())).thenReturn("stub-ref");
        when(repository.save(any())).thenAnswer(inv -> {
            SessionPackPurchased saved = inv.getArgument(0);
            if (saved.getPurchasedAt() == null) saved.setPurchasedAt(Instant.now());
            if (saved.getStatus() == null) saved.setStatus("ACTIVE");
            return saved;
        });
        when(mapper.toResponse(any(SessionPackPurchased.class), any())).thenAnswer(inv -> {
            SessionPackPurchased p = inv.getArgument(0);
            String name = inv.getArgument(1);
            return new SessionPackPurchasedResponse(
                p.getId(), p.getCoachId(), name,
                p.getSessionCount(), p.getCreditsRemaining(),
                p.getPurchasedAt(), p.getStatus());
        });

        SessionPackPurchasedResponse response = service.purchasePack(PARENT_ID, PLAYER_ID, COACH_ID, packId);

        assertThat(response.sessionCount()).isEqualTo(5);
        assertThat(response.creditsRemaining()).isEqualTo(5);
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.coachDisplayName()).isEqualTo("Test Coach");
    }

    // ----- helpers -----

    private SessionPackPurchased packWith(Long playerId, UUID coachId, int sessionCount, int creditsRemaining) {
        SessionPackPurchased pack = Instancio.of(SessionPackPurchased.class)
            .set(field(SessionPackPurchased::getPlayerId), playerId)
            .set(field(SessionPackPurchased::getCoachId), coachId)
            .set(field(SessionPackPurchased::getSessionCount), sessionCount)
            .set(field(SessionPackPurchased::getCreditsRemaining), creditsRemaining)
            .set(field(SessionPackPurchased::getStatus), "ACTIVE")
            .create();
        return pack;
    }
}
