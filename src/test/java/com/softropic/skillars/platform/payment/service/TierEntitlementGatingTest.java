package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachSubscriptionRepository;
import com.softropic.skillars.platform.payment.repo.CoachSubscriptionChangeRepository;
import com.softropic.skillars.platform.payment.repo.PaymentCoachSubscription;
import com.softropic.skillars.platform.payment.repo.PaymentCoachSubscriptionRepository;
import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscription;
import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscriptionRepository;
import com.softropic.skillars.platform.payment.repo.PlayerSubscriptionChangeRepository;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.security.repo.ParentPlayerLinkRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TierEntitlementGatingTest {

    @Mock PaymentCoachSubscriptionRepository paymentCoachSubscriptionRepository;
    @Mock PaymentPlayerSubscriptionRepository paymentPlayerSubscriptionRepository;
    @Mock CoachSubscriptionChangeRepository coachSubscriptionChangeRepository;
    @Mock PlayerSubscriptionChangeRepository playerSubscriptionChangeRepository;
    @Mock CoachSubscriptionRepository coachSubscriptionRepository;
    @Mock StripeCustomerRepository stripeCustomerRepository;
    @Mock ConfigService configService;
    @Mock StripeClient stripeClient;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock ParentPlayerLinkRepository parentPlayerLinkRepository;

    @InjectMocks SubscriptionService service;

    private static final UUID COACH_ID = UUID.randomUUID();
    private static final long PLAYER_ID = 55001L;

    // ─── Coach effective tier ─────────────────────────────────────────────────────

    @Test
    void activeCoach_returnsActualTier() {
        when(paymentCoachSubscriptionRepository.findByCoachId(COACH_ID))
            .thenReturn(Optional.of(coachSub("INSTRUCTOR", "ACTIVE")));

        assertThat(service.getEffectiveCoachTier(COACH_ID)).isEqualTo("INSTRUCTOR");
    }

    @Test
    void pastDueCoach_returnsScout() {
        when(paymentCoachSubscriptionRepository.findByCoachId(COACH_ID))
            .thenReturn(Optional.of(coachSub("ACADEMY", "PAST_DUE")));

        assertThat(service.getEffectiveCoachTier(COACH_ID)).isEqualTo("SCOUT");
    }

    @Test
    void cancelledCoach_returnsScout() {
        when(paymentCoachSubscriptionRepository.findByCoachId(COACH_ID))
            .thenReturn(Optional.of(coachSub("INSTRUCTOR", "CANCELLED")));

        assertThat(service.getEffectiveCoachTier(COACH_ID)).isEqualTo("SCOUT");
    }

    @Test
    void noCoachRecord_returnsScout() {
        when(paymentCoachSubscriptionRepository.findByCoachId(COACH_ID))
            .thenReturn(Optional.empty());

        assertThat(service.getEffectiveCoachTier(COACH_ID)).isEqualTo("SCOUT");
    }

    @Test
    void triallingCoach_returnsActualTier() {
        when(paymentCoachSubscriptionRepository.findByCoachId(COACH_ID))
            .thenReturn(Optional.of(coachSub("ACADEMY", "TRIALLING")));

        assertThat(service.getEffectiveCoachTier(COACH_ID)).isEqualTo("ACADEMY");
    }

    // ─── Player effective tier ────────────────────────────────────────────────────

    @Test
    void activePlayer_returnsActualTier() {
        when(paymentPlayerSubscriptionRepository.findByPlayerId(PLAYER_ID))
            .thenReturn(Optional.of(playerSub("PRO", "ACTIVE")));

        assertThat(service.getEffectivePlayerTier(PLAYER_ID)).isEqualTo("PRO");
    }

    @Test
    void pastDuePlayer_returnsAthlete() {
        when(paymentPlayerSubscriptionRepository.findByPlayerId(PLAYER_ID))
            .thenReturn(Optional.of(playerSub("SEMI_PRO", "PAST_DUE")));

        assertThat(service.getEffectivePlayerTier(PLAYER_ID)).isEqualTo("ATHLETE");
    }

    @Test
    void cancelledPlayer_returnsAthlete() {
        when(paymentPlayerSubscriptionRepository.findByPlayerId(PLAYER_ID))
            .thenReturn(Optional.of(playerSub("PRO", "CANCELLED")));

        assertThat(service.getEffectivePlayerTier(PLAYER_ID)).isEqualTo("ATHLETE");
    }

    @Test
    void noPlayerRecord_returnsAthlete() {
        when(paymentPlayerSubscriptionRepository.findByPlayerId(PLAYER_ID))
            .thenReturn(Optional.empty());

        assertThat(service.getEffectivePlayerTier(PLAYER_ID)).isEqualTo("ATHLETE");
    }

    // ─── ConfigService per-invocation call pattern ───────────────────────────────

    @Test
    void configServiceCalledPerGracePeriodInvocation() {
        when(configService.getLong("subscription.pastDue.gracePeriodDays")).thenReturn(7L);

        service.checkPastDueGracePeriod();
        service.checkPastDueGracePeriod();

        verify(configService, times(2)).getLong("subscription.pastDue.gracePeriodDays");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private PaymentCoachSubscription coachSub(String tier, String status) {
        PaymentCoachSubscription sub = new PaymentCoachSubscription();
        sub.setCoachId(COACH_ID);
        sub.setTier(tier);
        sub.setStatus(status);
        sub.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));
        return sub;
    }

    private PaymentPlayerSubscription playerSub(String tier, String status) {
        PaymentPlayerSubscription sub = new PaymentPlayerSubscription();
        sub.setPlayerId(PLAYER_ID);
        sub.setTier(tier);
        sub.setStatus(status);
        sub.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));
        return sub;
    }
}
