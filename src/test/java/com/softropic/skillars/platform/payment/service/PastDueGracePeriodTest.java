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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PastDueGracePeriodTest {

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
    private static final long PLAYER_ID = 99001L;

    @BeforeEach
    void setUp() {
        when(configService.getLong("subscription.pastDue.gracePeriodDays")).thenReturn(7L);
    }

    @Test
    void pastDueWithinGracePeriod_noDowngrade() {
        PaymentCoachSubscription sub = pastDueCoach(Instant.now().minus(5, ChronoUnit.DAYS));

        service.checkPastDueGracePeriod();

        assertThat(sub.getTier()).isEqualTo("INSTRUCTOR");
        assertThat(sub.getStatus()).isEqualTo("PAST_DUE");
        verify(paymentCoachSubscriptionRepository, never()).save(any());
    }

    @Test
    void pastDuePastGracePeriod_coachDowngradedToScout() {
        PaymentCoachSubscription sub = pastDueCoach(Instant.now().minus(8, ChronoUnit.DAYS));
        when(paymentCoachSubscriptionRepository.findByStatusAndPastDueSinceBefore(any(), any())).thenReturn(List.of(sub));
        when(coachSubscriptionRepository.findByCoachId(COACH_ID)).thenReturn(java.util.Optional.empty());

        service.checkPastDueGracePeriod();

        assertThat(sub.getTier()).isEqualTo("SCOUT");
        assertThat(sub.getStatus()).isEqualTo("CANCELLED");
        verify(paymentCoachSubscriptionRepository, times(1)).save(sub);
    }

    @Test
    void pastDuePastGracePeriod_playerDowngradedToAthlete() {
        PaymentPlayerSubscription sub = pastDuePlayer(Instant.now().minus(8, ChronoUnit.DAYS));
        when(paymentPlayerSubscriptionRepository.findByStatusAndPastDueSinceBefore(any(), any())).thenReturn(List.of(sub));

        service.checkPastDueGracePeriod();

        assertThat(sub.getTier()).isEqualTo("ATHLETE");
        assertThat(sub.getStatus()).isEqualTo("CANCELLED");
        verify(paymentPlayerSubscriptionRepository, times(1)).save(sub);
    }

    @Test
    void gracePeriodBoundary_exactlySevenDays_noDowngrade() {
        // Exactly 7 days — not yet past the boundary (boundary is < now - 7 days)
        PaymentCoachSubscription sub = pastDueCoach(Instant.now().minus(7, ChronoUnit.DAYS));

        service.checkPastDueGracePeriod();

        assertThat(sub.getTier()).isEqualTo("INSTRUCTOR");
    }

    @Test
    void updatedAtBumpDoesNotResetGracePeriodClock() {
        // updatedAt was bumped recently, but pastDueSince is old — must use pastDueSince
        PaymentCoachSubscription sub = pastDueCoach(Instant.now().minus(10, ChronoUnit.DAYS));
        sub.setUpdatedAt(Instant.now()); // recently updated — must NOT reset grace window
        when(paymentCoachSubscriptionRepository.findByStatusAndPastDueSinceBefore(any(), any())).thenReturn(List.of(sub));
        when(coachSubscriptionRepository.findByCoachId(COACH_ID)).thenReturn(java.util.Optional.empty());

        service.checkPastDueGracePeriod();

        // Grace period must be based on pastDueSince, not updatedAt
        assertThat(sub.getTier()).isEqualTo("SCOUT");
    }

    @Test
    void configServiceCalledPerInvocation_notCached() {
        service.checkPastDueGracePeriod();
        service.checkPastDueGracePeriod();
        service.checkPastDueGracePeriod();

        // Must be called once per invocation — not cached in a field
        verify(configService, times(3)).getLong("subscription.pastDue.gracePeriodDays");
    }

    private PaymentCoachSubscription pastDueCoach(Instant pastDueSince) {
        PaymentCoachSubscription sub = new PaymentCoachSubscription();
        sub.setCoachId(COACH_ID);
        sub.setTier("INSTRUCTOR");
        sub.setStatus("PAST_DUE");
        sub.setPastDueSince(pastDueSince);
        return sub;
    }

    private PaymentPlayerSubscription pastDuePlayer(Instant pastDueSince) {
        PaymentPlayerSubscription sub = new PaymentPlayerSubscription();
        sub.setPlayerId(PLAYER_ID);
        sub.setTier("SEMI_PRO");
        sub.setStatus("PAST_DUE");
        sub.setPastDueSince(pastDueSince);
        return sub;
    }
}
