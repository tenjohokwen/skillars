package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.SessionPackExpiredEvent;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchase;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;
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
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionPackForfeitureSchedulerTest {

    @Mock SessionPackPurchaseRepository sessionPackPurchaseRepository;
    @Mock CoachProfileRepository coachProfileRepository;
    @Mock UserRepository userRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock TransactionTemplate transactionTemplate;

    @InjectMocks SessionPackForfeitureScheduler scheduler;

    private static final UUID COACH_ID = UUID.randomUUID();
    private static final Long PARENT_ID = 3001L;
    private static final Long PLAYER_ID = 3002L;

    @BeforeEach
    void setUpTransactionTemplate() {
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    @Test
    void forfeitExpiredPacks_marksNotifiedAndPublishesEventOnce() {
        SessionPackPurchase purchase = buildPurchase();
        CoachProfile coach = new CoachProfile();
        coach.setDisplayName("Forfeit Coach");

        when(sessionPackPurchaseRepository.findExpiredNotYetNotified(any())).thenReturn(List.of(purchase));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        User parentUser = mock(User.class);
        when(parentUser.getEmail()).thenReturn("parent@test.com");
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(parentUser));
        when(sessionPackPurchaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        scheduler.forfeitExpiredPacks();

        assertThat(purchase.getExpiredNotifiedAt()).isNotNull();
        verify(sessionPackPurchaseRepository).save(purchase);

        ArgumentCaptor<SessionPackExpiredEvent> captor = ArgumentCaptor.forClass(SessionPackExpiredEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());
        assertThat(captor.getValue().getPlayerId()).isEqualTo(PLAYER_ID);
        assertThat(captor.getValue().getCoachDisplayName()).isEqualTo("Forfeit Coach");
    }

    @Test
    void forfeitExpiredPacks_secondRun_doesNotReNotifyAlreadyNotifiedPack() {
        // First run: pack is found and notified
        SessionPackPurchase purchase = buildPurchase();
        when(sessionPackPurchaseRepository.findExpiredNotYetNotified(any())).thenReturn(List.of(purchase));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.empty());
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.empty());
        when(sessionPackPurchaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        scheduler.forfeitExpiredPacks();
        assertThat(purchase.getExpiredNotifiedAt()).isNotNull();

        // Second run: repository query now excludes the already-notified pack (simulated by returning empty)
        when(sessionPackPurchaseRepository.findExpiredNotYetNotified(any())).thenReturn(List.of());
        scheduler.forfeitExpiredPacks();

        verify(eventPublisher, times(1)).publishEvent(any(SessionPackExpiredEvent.class));
    }

    @Test
    void forfeitExpiredPacks_oneFailure_othersContinue() {
        SessionPackPurchase purchase1 = buildPurchase();
        SessionPackPurchase purchase2 = buildPurchase();

        when(sessionPackPurchaseRepository.findExpiredNotYetNotified(any())).thenReturn(List.of(purchase1, purchase2));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.empty());
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.empty());
        when(sessionPackPurchaseRepository.save(purchase1)).thenThrow(new RuntimeException("DB error"));
        when(sessionPackPurchaseRepository.save(purchase2)).thenAnswer(inv -> inv.getArgument(0));

        scheduler.forfeitExpiredPacks();

        assertThat(purchase2.getExpiredNotifiedAt()).isNotNull();
        verify(sessionPackPurchaseRepository, times(2)).save(any());
    }

    @Test
    void forfeitExpiredPacks_noExpiredPacks_doesNothing() {
        when(sessionPackPurchaseRepository.findExpiredNotYetNotified(any())).thenReturn(List.of());

        scheduler.forfeitExpiredPacks();

        verify(eventPublisher, never()).publishEvent(any());
        verify(sessionPackPurchaseRepository, never()).save(any());
    }

    private SessionPackPurchase buildPurchase() {
        SessionPackPurchase purchase = new SessionPackPurchase();
        purchase.setPurchaseId(UUID.randomUUID());
        purchase.setParentId(PARENT_ID);
        purchase.setPlayerId(PLAYER_ID);
        purchase.setCoachId(COACH_ID);
        purchase.setRemainingSessions(3);
        purchase.setExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
        return purchase;
    }
}
