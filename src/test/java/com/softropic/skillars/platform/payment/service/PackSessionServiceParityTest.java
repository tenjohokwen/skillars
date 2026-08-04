package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchase;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PackSessionServiceParityTest {

    @Mock SessionPackPurchaseRepository sessionPackPurchaseRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock BookingRepository bookingRepository;
    @Mock BookingService bookingService;
    @Mock ConfigService configService;
    @Mock CoachProfileRepository coachProfileRepository;
    @Mock UserRepository userRepository;

    @InjectMocks PackSessionService service;

    private static final Long PLAYER_ID = 8001L;
    private static final UUID COACH_ID = UUID.randomUUID();

    @Test
    void hasActivePack_activePackExists_returnsTrue() {
        SessionPackPurchase pack = purchase(3);
        when(sessionPackPurchaseRepository.findActivePacks(any(Long.class), any(UUID.class), any(Instant.class)))
            .thenReturn(List.of(pack));

        assertThat(service.hasActivePack(PLAYER_ID, COACH_ID)).isTrue();
    }

    @Test
    void hasActivePack_noActivePack_returnsFalse() {
        when(sessionPackPurchaseRepository.findActivePacks(any(Long.class), any(UUID.class), any(Instant.class)))
            .thenReturn(List.of());

        assertThat(service.hasActivePack(PLAYER_ID, COACH_ID)).isFalse();
    }

    @Test
    void getActivePackId_activePackExists_returnsFirstResultFromFindActivePacks() {
        // findActivePacks is queried ORDER BY createdAt ASC — the repository, not this method,
        // is responsible for oldest-first ordering; this method just takes the first result.
        UUID oldestId = UUID.randomUUID();
        SessionPackPurchase older = purchase(2);
        older.setPurchaseId(oldestId);
        SessionPackPurchase newer = purchase(5);
        when(sessionPackPurchaseRepository.findActivePacks(any(Long.class), any(UUID.class), any(Instant.class)))
            .thenReturn(List.of(older, newer));

        UUID result = service.getActivePackId(PLAYER_ID, COACH_ID);

        assertThat(result).isEqualTo(oldestId);
    }

    @Test
    void getActivePackId_noActivePack_fallsBackToMostRecentlyCreatedPack() {
        UUID fallbackId = UUID.randomUUID();
        SessionPackPurchase fallback = purchase(0);
        fallback.setPurchaseId(fallbackId);

        when(sessionPackPurchaseRepository.findActivePacks(any(Long.class), any(UUID.class), any(Instant.class)))
            .thenReturn(List.of());
        when(sessionPackPurchaseRepository.findTopByPlayerIdAndCoachIdOrderByCreatedAtDesc(PLAYER_ID, COACH_ID))
            .thenReturn(Optional.of(fallback));

        UUID result = service.getActivePackId(PLAYER_ID, COACH_ID);

        assertThat(result).isEqualTo(fallbackId);
    }

    @Test
    void getActivePackId_noActivePackAndNoFallback_returnsNull() {
        when(sessionPackPurchaseRepository.findActivePacks(any(Long.class), any(UUID.class), any(Instant.class)))
            .thenReturn(List.of());
        when(sessionPackPurchaseRepository.findTopByPlayerIdAndCoachIdOrderByCreatedAtDesc(PLAYER_ID, COACH_ID))
            .thenReturn(Optional.empty());

        assertThat(service.getActivePackId(PLAYER_ID, COACH_ID)).isNull();
    }

    // ─── findActivePackId (Deferred-11 AC 7: TOCTOU fix) ──────────────────────────

    @Test
    void findActivePackId_activePackExists_returnsFirstResultFromFindActivePacks() {
        UUID oldestId = UUID.randomUUID();
        SessionPackPurchase older = purchase(2);
        older.setPurchaseId(oldestId);
        SessionPackPurchase newer = purchase(5);
        when(sessionPackPurchaseRepository.findActivePacks(any(Long.class), any(UUID.class), any(Instant.class)))
            .thenReturn(List.of(older, newer));

        UUID result = service.findActivePackId(PLAYER_ID, COACH_ID);

        assertThat(result).isEqualTo(oldestId);
    }

    @Test
    void findActivePackId_noActivePack_throwsAndNeverConsultsUnfilteredFallback() {
        when(sessionPackPurchaseRepository.findActivePacks(any(Long.class), any(UUID.class), any(Instant.class)))
            .thenReturn(List.of());

        assertThatThrownBy(() -> service.findActivePackId(PLAYER_ID, COACH_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("credits");

        // The whole point of AC 7: no fallback to the unfiltered "most recent pack ever" query
        // that could attach an exhausted/expired pack instead of failing loudly.
        verify(sessionPackPurchaseRepository, never())
            .findTopByPlayerIdAndCoachIdOrderByCreatedAtDesc(any(Long.class), any(UUID.class));
    }

    private SessionPackPurchase purchase(int remainingSessions) {
        SessionPackPurchase p = new SessionPackPurchase();
        p.setPurchaseId(UUID.randomUUID());
        p.setPlayerId(PLAYER_ID);
        p.setCoachId(COACH_ID);
        p.setRemainingSessions(remainingSessions);
        p.setExpiresAt(Instant.now().plusSeconds(3600));
        return p;
    }
}
