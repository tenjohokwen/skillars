package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.SessionPackExpiredEvent;
import com.softropic.skillars.platform.booking.contract.SessionPackExpiryWarningEvent;
import com.softropic.skillars.platform.booking.repo.SessionPackPurchased;
import com.softropic.skillars.platform.booking.repo.SessionPackPurchasedRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionPackExpirySchedulerTest {

    @Mock SessionPackPurchasedRepository repository;
    @Mock CoachProfileRepository coachProfileRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock UserRepository userRepository;
    @Mock TransactionTemplate transactionTemplate;

    @InjectMocks SessionPackExpiryScheduler scheduler;

    @BeforeEach
    void setUpTransactionTemplate() {
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    private static final UUID COACH_ID = UUID.randomUUID();
    private static final Long PARENT_ID = 1001L;

    @Test
    void expireActivePacks_setsStatusExpiredAndPublishesEvent() {
        SessionPackPurchased pack = buildPack(3);
        CoachProfile coach = buildCoach("Test Coach");

        when(repository.findExpiredActivePacks(any())).thenReturn(List.of(pack));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        var parentUser = buildUser("parent@test.com");
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(parentUser));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findPacksNeedingWarning30d(any(Instant.class), any(Instant.class))).thenReturn(List.of());
        when(repository.findPacksNeedingWarning7d(any(Instant.class), any(Instant.class))).thenReturn(List.of());

        scheduler.runExpiryAndWarnings();

        assertThat(pack.getStatus()).isEqualTo("EXPIRED");
        verify(repository).save(pack);

        ArgumentCaptor<SessionPackExpiredEvent> captor = ArgumentCaptor.forClass(SessionPackExpiredEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getPlayerId()).isEqualTo(pack.getPlayerId());
        assertThat(captor.getValue().getCreditsRemaining()).isEqualTo(3);
        assertThat(captor.getValue().getCoachDisplayName()).isEqualTo("Test Coach");
    }

    @Test
    void expireActivePacks_zeroCredits_stillExpires() {
        SessionPackPurchased pack = buildPack(0);
        pack.setStatus("ACTIVE");

        when(repository.findExpiredActivePacks(any())).thenReturn(List.of(pack));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.empty());
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findPacksNeedingWarning30d(any(Instant.class), any(Instant.class))).thenReturn(List.of());
        when(repository.findPacksNeedingWarning7d(any(Instant.class), any(Instant.class))).thenReturn(List.of());

        scheduler.runExpiryAndWarnings();

        assertThat(pack.getStatus()).isEqualTo("EXPIRED");
        verify(repository).save(pack);
    }

    @Test
    void sendWarnings_30dThreshold_sendsWarningAndStamps() {
        SessionPackPurchased pack = buildPack(5);
        CoachProfile coach = buildCoach("Warning Coach");
        Instant now = Instant.now();

        when(repository.findExpiredActivePacks(any())).thenReturn(List.of());
        when(repository.findPacksNeedingWarning30d(any(Instant.class), any(Instant.class))).thenReturn(List.of(pack));
        when(repository.findPacksNeedingWarning7d(any(Instant.class), any(Instant.class))).thenReturn(List.of());
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        var parentUser = buildUser("parent@test.com");
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(parentUser));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        scheduler.runExpiryAndWarnings();

        assertThat(pack.getWarning30dSentAt()).isNotNull();
        verify(repository, atLeastOnce()).save(pack);

        ArgumentCaptor<SessionPackExpiryWarningEvent> captor = ArgumentCaptor.forClass(SessionPackExpiryWarningEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getWarningThreshold()).isEqualTo("30d");
    }

    @Test
    void sendWarnings_7dThreshold_sendsWarningAndStamps() {
        SessionPackPurchased pack = buildPack(2);
        CoachProfile coach = buildCoach("7d Coach");

        when(repository.findExpiredActivePacks(any())).thenReturn(List.of());
        when(repository.findPacksNeedingWarning30d(any(Instant.class), any(Instant.class))).thenReturn(List.of());
        when(repository.findPacksNeedingWarning7d(any(Instant.class), any(Instant.class))).thenReturn(List.of(pack));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        var parentUser = buildUser("parent@test.com");
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(parentUser));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        scheduler.runExpiryAndWarnings();

        assertThat(pack.getWarning7dSentAt()).isNotNull();
        ArgumentCaptor<SessionPackExpiryWarningEvent> captor = ArgumentCaptor.forClass(SessionPackExpiryWarningEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getWarningThreshold()).isEqualTo("7d");
    }

    @Test
    void sendWarnings_idempotent_noDuplicates() {
        when(repository.findExpiredActivePacks(any())).thenReturn(List.of());
        when(repository.findPacksNeedingWarning30d(any(Instant.class), any(Instant.class))).thenReturn(List.of());
        when(repository.findPacksNeedingWarning7d(any(Instant.class), any(Instant.class))).thenReturn(List.of());

        scheduler.runExpiryAndWarnings();

        verify(eventPublisher, never()).publishEvent(any(SessionPackExpiryWarningEvent.class));
    }

    @Test
    void expireActivePacks_oneFailure_othersContinue() {
        SessionPackPurchased pack1 = buildPack(3);
        SessionPackPurchased pack2 = buildPack(1);

        when(repository.findExpiredActivePacks(any())).thenReturn(List.of(pack1, pack2));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.empty());
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.empty());
        when(repository.save(pack1)).thenThrow(new RuntimeException("DB error on pack1"));
        when(repository.save(pack2)).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findPacksNeedingWarning30d(any(Instant.class), any(Instant.class))).thenReturn(List.of());
        when(repository.findPacksNeedingWarning7d(any(Instant.class), any(Instant.class))).thenReturn(List.of());

        // Should not throw — exceptions are caught per-pack
        scheduler.runExpiryAndWarnings();

        // pack2 was still processed despite pack1 failing
        assertThat(pack2.getStatus()).isEqualTo("EXPIRED");
        verify(repository, times(2)).save(any());
    }

    // ----- helpers -----

    private SessionPackPurchased buildPack(int credits) {
        SessionPackPurchased pack = new SessionPackPurchased();
        pack.setPlayerId(2001L);
        pack.setCoachId(COACH_ID);
        pack.setParentId(PARENT_ID);
        pack.setSessionCount(credits + 2);
        pack.setCreditsRemaining(credits);
        pack.setStatus("ACTIVE");
        pack.setExpiresAt(Instant.now().minusSeconds(1));
        pack.setPurchasedAt(Instant.now().minusSeconds(3600));
        return pack;
    }

    private CoachProfile buildCoach(String displayName) {
        CoachProfile coach = new CoachProfile();
        coach.setDisplayName(displayName);
        coach.setCanonicalTimezone("UTC");
        return coach;
    }

    private com.softropic.skillars.platform.security.repo.User buildUser(String email) {
        com.softropic.skillars.platform.security.repo.User user =
            mock(com.softropic.skillars.platform.security.repo.User.class);
        when(user.getEmail()).thenReturn(email);
        return user;
    }
}
