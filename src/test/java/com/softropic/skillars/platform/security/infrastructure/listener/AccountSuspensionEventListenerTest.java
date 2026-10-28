package com.softropic.skillars.platform.security.infrastructure.listener;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.security.contract.event.AccountSuspensionRequestedEvent;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.RefreshTokenRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B5: a CSAM suspension whose owner resolves to no account must not end silently in a log line —
 * the listener throws, and ModerationOrchestrationService turns that into an urgent
 * "account suspension FAILED" admin alert. (Resolution and session revocation are covered by
 * AuthResourceIT.)
 */
class AccountSuspensionEventListenerTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final PlayerProfileRepository playerProfileRepository = mock(PlayerProfileRepository.class);
    private final PessimisticLockRetryer lockRetryer = mock(PessimisticLockRetryer.class);
    private final AccountSuspensionEventListener listener = new AccountSuspensionEventListener(
        userRepository, refreshTokenRepository, playerProfileRepository, lockRetryer);

    @Test
    void unresolvableOwner_throwsSoThePublisherAlertsAdmins() {
        UUID coachProfileId = UUID.randomUUID();
        when(userRepository.findUserIdByCoachProfileId(coachProfileId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> listener.onAccountSuspensionRequested(
            new AccountSuspensionRequestedEvent(coachProfileId.toString(), UUID.randomUUID())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("user not found");
        verify(refreshTokenRepository, never()).markAllUsedByUserId(any());
    }

    @Test
    void ownerResolvesButUserRowVanished_throws() {
        UUID coachProfileId = UUID.randomUUID();
        when(userRepository.findUserIdByCoachProfileId(coachProfileId)).thenReturn(Optional.of(42L));
        when(lockRetryer.withBoundedRetry(anyString(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> listener.onAccountSuspensionRequested(
            new AccountSuspensionRequestedEvent(coachProfileId.toString(), UUID.randomUUID())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("vanished");
        verify(refreshTokenRepository, never()).markAllUsedByUserId(any());
    }
}
