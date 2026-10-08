package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.contract.PlayerSubscriptionResponse;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscription;
import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscriptionRepository;
import com.softropic.skillars.platform.security.repo.ParentPlayerLinkRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
