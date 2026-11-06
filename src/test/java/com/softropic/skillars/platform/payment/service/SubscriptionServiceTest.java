package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.contract.PlayerSubscriptionResponse;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscription;
import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscriptionRepository;
import com.softropic.skillars.platform.payment.repo.PlayerSubscriptionChange;
import com.softropic.skillars.platform.payment.repo.PlayerSubscriptionChangeRepository;
import com.softropic.skillars.platform.security.repo.ParentPlayerLinkRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

// skillars-deferred-149 AC1: SubscriptionService.assertPlayerOwnership had no self-registered-player
// disjunct — PlayerOwnershipGuard.check already authorizes a self-registered player at the controller
// layer (existsByIdAndUserId), but the service call one line later still rejected them with only
// existsByParentIdAndPlayerId. This file is new — no prior unit test for SubscriptionService existed.
@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    @Mock ParentPlayerLinkRepository parentPlayerLinkRepository;
    @Mock PlayerProfileRepository playerProfileRepository;
    @Mock PaymentPlayerSubscriptionRepository paymentPlayerSubscriptionRepository;
    @Mock PlayerSubscriptionChangeRepository playerSubscriptionChangeRepository;

    @InjectMocks SubscriptionService subscriptionService;

    private static final Long CALLER_ID = 7001L;
    private static final Long OWN_PLAYER_ID = 456L;
    private static final Long OTHER_PLAYER_ID = 999L;

    @Test
    void getPlayerSubscription_selfRegisteredPlayerOwnProfile_succeeds() {
        when(parentPlayerLinkRepository.existsByParentIdAndPlayerId(CALLER_ID, OWN_PLAYER_ID))
            .thenReturn(false);
        when(playerProfileRepository.existsByIdAndUserId(OWN_PLAYER_ID, CALLER_ID))
            .thenReturn(true);

        PaymentPlayerSubscription sub = new PaymentPlayerSubscription();
        sub.setPlayerId(OWN_PLAYER_ID);
        when(paymentPlayerSubscriptionRepository.findByPlayerId(OWN_PLAYER_ID))
            .thenReturn(Optional.of(sub));

        PlayerSubscriptionResponse response = subscriptionService.getPlayerSubscription(CALLER_ID, OWN_PLAYER_ID);

        assertThat(response.playerId()).isEqualTo(OWN_PLAYER_ID);
    }

    // Pitchside SUB-06: the plan page decides between subscribing and changing tier on this flag, not on the
    // tier, because ATHLETE is both the free fallback and a priced tier.
    @Test
    void getPlayerSubscription_reportsWhetherAStripeSubscriptionBacksThePlan() {
        when(parentPlayerLinkRepository.existsByParentIdAndPlayerId(CALLER_ID, OWN_PLAYER_ID)).thenReturn(true);
        PaymentPlayerSubscription sub = new PaymentPlayerSubscription();
        sub.setPlayerId(OWN_PLAYER_ID);
        sub.setTier("ATHLETE");
        when(paymentPlayerSubscriptionRepository.findByPlayerId(OWN_PLAYER_ID)).thenReturn(Optional.of(sub));

        assertThat(subscriptionService.getPlayerSubscription(CALLER_ID, OWN_PLAYER_ID).subscribed()).isFalse();

        sub.setStripeSubscriptionId("sub_athlete");
        sub.setStatus("ACTIVE");
        assertThat(subscriptionService.getPlayerSubscription(CALLER_ID, OWN_PLAYER_ID).subscribed()).isTrue();

        // A CANCELLED row that kept its Stripe id subscribes afresh (assertCanSubscribe cancels the stale one).
        sub.setStatus("CANCELLED");
        assertThat(subscriptionService.getPlayerSubscription(CALLER_ID, OWN_PLAYER_ID).subscribed()).isFalse();
    }

    // Owner decision 2026-11-06: the plan page shows a pending paid downgrade and offers to take it back.
    @Test
    void getPlayerSubscription_reportsAPendingDowngrade() {
        when(parentPlayerLinkRepository.existsByParentIdAndPlayerId(CALLER_ID, OWN_PLAYER_ID)).thenReturn(true);
        PaymentPlayerSubscription sub = new PaymentPlayerSubscription();
        sub.setPlayerId(OWN_PLAYER_ID);
        sub.setTier("PRO");
        when(paymentPlayerSubscriptionRepository.findByPlayerId(OWN_PLAYER_ID)).thenReturn(Optional.of(sub));
        Instant periodEnd = Instant.parse("2027-03-12T00:00:00Z");
        PlayerSubscriptionChange change = new PlayerSubscriptionChange();
        change.setToTier("SEMI_PRO");
        change.setEffectiveAt(periodEnd);
        when(playerSubscriptionChangeRepository
            .findFirstByPlayerIdAndAppliedFalseAndVoidedAtIsNullOrderByEffectiveAtDesc(OWN_PLAYER_ID))
            .thenReturn(Optional.of(change));

        PlayerSubscriptionResponse shown = subscriptionService.getPlayerSubscription(CALLER_ID, OWN_PLAYER_ID);

        assertThat(shown.pendingTier()).isEqualTo("SEMI_PRO");
        assertThat(shown.pendingEffectiveAt()).isEqualTo(periodEnd);
    }

    @Test
    void getPlayerSubscription_selfRegisteredPlayerOtherProfile_throwsPlayerOwnership() {
        // Not parent-linked AND not the caller's own self-registered profile — must still be rejected.
        when(parentPlayerLinkRepository.existsByParentIdAndPlayerId(CALLER_ID, OTHER_PLAYER_ID))
            .thenReturn(false);
        when(playerProfileRepository.existsByIdAndUserId(OTHER_PLAYER_ID, CALLER_ID))
            .thenReturn(false);

        assertThatThrownBy(() -> subscriptionService.getPlayerSubscription(CALLER_ID, OTHER_PLAYER_ID))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessage("payment.subscription.playerOwnership");
    }
}
