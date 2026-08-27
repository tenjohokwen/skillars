package com.softropic.skillars.platform.marketplace.service;

import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlayerProfileServiceTest {

    @Mock private PlayerProfileRepository playerProfileRepository;
    @Mock private UserRepository userRepository;

    private PlayerProfileService service;

    private static final Long PLAYER_ID = 500L;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new PlayerProfileService(playerProfileRepository, userRepository);
    }

    @Test
    void getParentEmailByPlayerId_playerWithParent_returnsEmail() {
        when(playerProfileRepository.findParentEmailByPlayerId(PLAYER_ID))
            .thenReturn(Optional.of("parent@example.com"));

        assertThat(service.getParentEmailByPlayerId(PLAYER_ID)).isEqualTo("parent@example.com");
    }

    @Test
    void getParentEmailByPlayerId_selfRegisteredAdultNoParent_returnsNullGracefully() {
        when(playerProfileRepository.findParentEmailByPlayerId(PLAYER_ID)).thenReturn(Optional.empty());

        assertThat(service.getParentEmailByPlayerId(PLAYER_ID)).isNull();
    }

    @Test
    void getParentEmailByPlayerId_playerOrParentNotFound_returnsNullGracefully() {
        // Single-query JOIN never distinguishes "player missing", "no parent_id", or "parent row
        // gone" — all fall through to no matching row, same graceful null as the self-registered case.
        when(playerProfileRepository.findParentEmailByPlayerId(PLAYER_ID)).thenReturn(Optional.empty());

        assertThat(service.getParentEmailByPlayerId(PLAYER_ID)).isNull();
    }
}
