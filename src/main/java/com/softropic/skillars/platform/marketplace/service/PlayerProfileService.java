package com.softropic.skillars.platform.marketplace.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PlayerProfileService {

    private final PlayerProfileRepository playerProfileRepository;

    @Transactional(readOnly = true)
    public Long getParentIdByPlayerId(Long playerId) {
        return playerProfileRepository.findById(playerId)
            .map(p -> p.getParentId())
            .orElseThrow(() -> new ResourceNotFoundException("Player not found: " + playerId, "player_profile"));
    }
}
