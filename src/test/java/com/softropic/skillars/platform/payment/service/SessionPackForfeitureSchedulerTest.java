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
import static org.mockito.Mockito.lenient;
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
        lenient().when(sessionPackPurchaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private CoachProfile coach(String displayName) {
        CoachProfile c = new CoachProfile();
        c.setDisplayName(displayName);
        return c;
    }

    private User userWithEmail(String email) {
        User u = mock(User.class);
        lenient().when(u.getEmail()).thenReturn(email);
        return u;
    }

    @Test
    void forfeitExpiredPacks_coachAndEmailPresent_marksNotifiedAndPublishesEventOnce() {
        SessionPackPurchase purchase = buildPurchase(COACH_ID);
        when(sessionPackPurchaseRepository.findExpiredNotYetNotified(any())).thenReturn(List.of(purchase));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach("Forfeit Coach")));
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(userWithEmail("parent@test.com")));

        scheduler.forfeitExpiredPacks();

        assertThat(purchase.getExpiredNotifiedAt()).isNotNull();
        verify(sessionPackPurchaseRepository).save(purchase);

        ArgumentCaptor<SessionPackExpiredEvent> captor = ArgumentCaptor.forClass(SessionPackExpiredEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());
        assertThat(captor.getValue().getPlayerId()).isEqualTo(PLAYER_ID);
        assertThat(captor.getValue().getCoachDisplayName()).isEqualTo("Forfeit Coach");
    }

    @Test
    void forfeitExpiredPacks_missingCoach_isLeftUnstamped_soItKeepsSurfacing() {
        // skillars-deferred-103 AC7 + P5: coach_id is FK-backed (fk_spp_coach), so a missing coach
        // is a data-integrity failure. It is deliberately NOT stamped — the pack keeps being
        // selected and the ERROR repeats until the row is repaired.
        SessionPackPurchase purchase = buildPurchase(COACH_ID);
        when(sessionPackPurchaseRepository.findExpiredNotYetNotified(any())).thenReturn(List.of(purchase));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.empty());

        scheduler.forfeitExpiredPacks();

        assertThat(purchase.getExpiredNotifiedAt()).isNull();
        verify(sessionPackPurchaseRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void forfeitExpiredPacks_blankParentEmail_finalisesWithoutNotifying() {
        // skillars-deferred-103 P5: a parent legitimately without an email is not a repairable bug
        // and a retry cannot change the outcome — the forfeiture is stamped once (so the pack stops
        // being re-selected every hour) and only the notification is skipped.
        SessionPackPurchase purchase = buildPurchase(COACH_ID);
        when(sessionPackPurchaseRepository.findExpiredNotYetNotified(any())).thenReturn(List.of(purchase));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach("Coach")));
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(userWithEmail("  ")));

        scheduler.forfeitExpiredPacks();

        assertThat(purchase.getExpiredNotifiedAt()).isNotNull();
        verify(sessionPackPurchaseRepository).save(purchase);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void forfeitExpiredPacks_secondRun_doesNotReNotifyAlreadyFinalisedPack() {
        SessionPackPurchase purchase = buildPurchase(COACH_ID);
        when(sessionPackPurchaseRepository.findExpiredNotYetNotified(any()))
            .thenReturn(List.of(purchase))
            .thenReturn(List.of());
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach("Coach")));
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(userWithEmail("parent@test.com")));

        scheduler.forfeitExpiredPacks();
        assertThat(purchase.getExpiredNotifiedAt()).isNotNull();

        scheduler.forfeitExpiredPacks();

        verify(eventPublisher, times(1)).publishEvent(any(SessionPackExpiredEvent.class));
    }

    @Test
    void forfeitExpiredPacks_oneFailure_othersContinue() {
        UUID otherCoach = UUID.randomUUID();
        SessionPackPurchase failing = buildPurchase(COACH_ID);
        SessionPackPurchase healthy = buildPurchase(otherCoach);

        when(sessionPackPurchaseRepository.findExpiredNotYetNotified(any()))
            .thenReturn(List.of(failing, healthy));
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach("Coach A")));
        when(coachProfileRepository.findById(otherCoach)).thenReturn(Optional.of(coach("Coach B")));
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(userWithEmail("parent@test.com")));
        when(sessionPackPurchaseRepository.save(failing)).thenThrow(new RuntimeException("DB error"));
        when(sessionPackPurchaseRepository.save(healthy)).thenAnswer(inv -> inv.getArgument(0));

        scheduler.forfeitExpiredPacks();

        assertThat(healthy.getExpiredNotifiedAt()).isNotNull();
        verify(sessionPackPurchaseRepository).save(healthy);
    }

    @Test
    void forfeitExpiredPacks_noExpiredPacks_doesNothing() {
        when(sessionPackPurchaseRepository.findExpiredNotYetNotified(any())).thenReturn(List.of());

        scheduler.forfeitExpiredPacks();

        verify(eventPublisher, never()).publishEvent(any());
        verify(sessionPackPurchaseRepository, never()).save(any());
    }

    private SessionPackPurchase buildPurchase(UUID coachId) {
        SessionPackPurchase purchase = new SessionPackPurchase();
        purchase.setPurchaseId(UUID.randomUUID());
        purchase.setParentId(PARENT_ID);
        purchase.setPlayerId(PLAYER_ID);
        purchase.setCoachId(coachId);
        purchase.setRemainingSessions(3);
        purchase.setExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
        return purchase;
    }
}
