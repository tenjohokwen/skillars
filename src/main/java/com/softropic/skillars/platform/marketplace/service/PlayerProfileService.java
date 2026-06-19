package com.softropic.skillars.platform.marketplace.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Period;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PlayerProfileService {

    private final PlayerProfileRepository playerProfileRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public Long getParentIdByPlayerId(Long playerId) {
        return playerProfileRepository.findById(playerId)
            .map(PlayerProfile::getParentId)
            .orElseThrow(() -> new ResourceNotFoundException("Player not found: " + playerId, "player_profile"));
    }

    @Transactional(readOnly = true)
    public String getPlayerNameByPlayerId(Long playerId) {
        return playerProfileRepository.findById(playerId)
            .map(PlayerProfile::getName)
            .orElse("Unknown Player");
    }

    @Transactional(readOnly = true)
    public int getPlayerAgeByPlayerId(Long playerId) {
        return playerProfileRepository.findById(playerId)
            .map(p -> p.getDateOfBirth() != null
                ? Math.max(0, Period.between(p.getDateOfBirth(), LocalDate.now()).getYears())
                : 0)
            .orElse(0);
    }

    @Transactional(readOnly = true)
    public String getParentEmailByPlayerId(Long playerId) {
        Long parentId = getParentIdByPlayerId(playerId);
        return userRepository.findById(parentId)
            .map(u -> u.getEmail())
            .orElse(null);
    }

    @Transactional(readOnly = true)
    public String getParentNameByPlayerId(Long playerId) {
        Long parentId = getParentIdByPlayerId(playerId);
        return userRepository.findById(parentId)
            .map(u -> {
                String name = String.join(" ",
                    Objects.toString(u.getFirstName(), ""),
                    Objects.toString(u.getLastName(), "")).trim();
                return name.isEmpty() ? "Parent" : name;
            })
            .orElse("Parent");
    }
}
