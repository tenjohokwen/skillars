package com.softropic.skillars.platform.payment.adapter;

import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscription;
import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlayerSubscriptionQueryAdapterTest {

    @Mock PaymentPlayerSubscriptionRepository playerSubscriptionRepository;

    @InjectMocks PlayerSubscriptionQueryAdapter adapter;

    private static final long PLAYER_ID = 77001L;

    // ─── hasActiveYearlySubscription ─────────────────────────────────────────────

    @Test
    void activeYearlyWithFuturePeriodEnd_returnsTrue() {
        when(playerSubscriptionRepository.findByPlayerId(PLAYER_ID))
            .thenReturn(Optional.of(sub("ACTIVE", "YEARLY", futureEnd())));

        assertThat(adapter.hasActiveYearlySubscription(PLAYER_ID)).isTrue();
    }

    @Test
    void activeMonthly_returnsFalse() {
        when(playerSubscriptionRepository.findByPlayerId(PLAYER_ID))
            .thenReturn(Optional.of(sub("ACTIVE", "MONTHLY", futureEnd())));

        assertThat(adapter.hasActiveYearlySubscription(PLAYER_ID)).isFalse();
    }

    @Test
    void cancelledYearly_returnsFalse() {
        when(playerSubscriptionRepository.findByPlayerId(PLAYER_ID))
            .thenReturn(Optional.of(sub("CANCELLED", "YEARLY", futureEnd())));

        assertThat(adapter.hasActiveYearlySubscription(PLAYER_ID)).isFalse();
    }

    @Test
    void activeYearlyWithPastPeriodEnd_returnsFalse() {
        when(playerSubscriptionRepository.findByPlayerId(PLAYER_ID))
            .thenReturn(Optional.of(sub("ACTIVE", "YEARLY", pastEnd())));

        assertThat(adapter.hasActiveYearlySubscription(PLAYER_ID)).isFalse();
    }

    @Test
    void triallingYearly_returnsTrue() {
        when(playerSubscriptionRepository.findByPlayerId(PLAYER_ID))
            .thenReturn(Optional.of(sub("TRIALLING", "YEARLY", futureEnd())));

        assertThat(adapter.hasActiveYearlySubscription(PLAYER_ID)).isTrue();
    }

    @Test
    void noRecord_returnsFalse() {
        when(playerSubscriptionRepository.findByPlayerId(PLAYER_ID))
            .thenReturn(Optional.empty());

        assertThat(adapter.hasActiveYearlySubscription(PLAYER_ID)).isFalse();
    }

    // ─── hasAnyActiveSubscription ─────────────────────────────────────────────────

    @Test
    void activeMonthlyWithFuturePeriodEnd_returnsTrue() {
        when(playerSubscriptionRepository.findByPlayerId(PLAYER_ID))
            .thenReturn(Optional.of(sub("ACTIVE", "MONTHLY", futureEnd())));

        assertThat(adapter.hasAnyActiveSubscription(PLAYER_ID)).isTrue();
    }

    @Test
    void activeQuarterly_returnsTrue() {
        when(playerSubscriptionRepository.findByPlayerId(PLAYER_ID))
            .thenReturn(Optional.of(sub("ACTIVE", "QUARTERLY", futureEnd())));

        assertThat(adapter.hasAnyActiveSubscription(PLAYER_ID)).isTrue();
    }

    @Test
    void cancelledMonthly_returnsFalse() {
        when(playerSubscriptionRepository.findByPlayerId(PLAYER_ID))
            .thenReturn(Optional.of(sub("CANCELLED", "MONTHLY", futureEnd())));

        assertThat(adapter.hasAnyActiveSubscription(PLAYER_ID)).isFalse();
    }

    @Test
    void activeMonthlyWithPastPeriodEnd_returnsFalse() {
        when(playerSubscriptionRepository.findByPlayerId(PLAYER_ID))
            .thenReturn(Optional.of(sub("ACTIVE", "MONTHLY", pastEnd())));

        assertThat(adapter.hasAnyActiveSubscription(PLAYER_ID)).isFalse();
    }

    @Test
    void noRecord_anyActive_returnsFalse() {
        when(playerSubscriptionRepository.findByPlayerId(PLAYER_ID))
            .thenReturn(Optional.empty());

        assertThat(adapter.hasAnyActiveSubscription(PLAYER_ID)).isFalse();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private PaymentPlayerSubscription sub(String status, String billingInterval, Instant periodEnd) {
        PaymentPlayerSubscription sub = new PaymentPlayerSubscription();
        sub.setPlayerId(PLAYER_ID);
        sub.setStatus(status);
        sub.setBillingInterval(billingInterval);
        sub.setCurrentPeriodEnd(periodEnd);
        return sub;
    }

    private Instant futureEnd() {
        return Instant.now().plus(30, ChronoUnit.DAYS);
    }

    private Instant pastEnd() {
        return Instant.now().minus(1, ChronoUnit.DAYS);
    }
}
