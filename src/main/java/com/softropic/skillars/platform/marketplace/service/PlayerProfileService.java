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

    /**
     * skillars-deferred-90 AC13: batched {@link #getPlayerNameByPlayerId}. One {@code findAllById}
     * instead of one lookup per id. Ids with no profile row are absent from the map; callers
     * substitute their own fallback label.
     */
    @Transactional(readOnly = true)
    public java.util.Map<Long, String> getPlayerNamesByPlayerIds(java.util.Collection<Long> playerIds) {
        if (playerIds == null || playerIds.isEmpty()) {
            return java.util.Map.of();
        }
        // Null-tolerant on purpose (skillars-deferred-91 code review, decision D12):
        // Collectors.toMap throws NPE on a null VALUE, which made this method unusable from
        // MessagingService.buildSummaryContext — the one AC19 asked to route through it. A null name
        // is SKIPPED rather than stored, because Map.getOrDefault returns the stored null when the
        // key is present, which would defeat every caller's "Unknown Player" default.
        java.util.Map<Long, String> namesById = new java.util.HashMap<>();
        playerProfileRepository.findAllById(playerIds).forEach(pp -> {
            if (pp.getName() != null) {
                namesById.putIfAbsent(pp.getId(), pp.getName());
            }
        });
        return namesById;
    }

    @Transactional(readOnly = true)
    public int getPlayerAgeByPlayerId(Long playerId) {
        return playerProfileRepository.findById(playerId)
            .map(p -> p.getDateOfBirth() != null
                ? Math.max(0, Period.between(p.getDateOfBirth(), LocalDate.now()).getYears())
                : 0)
            .orElse(0);
    }

    // Single-query JOIN — avoids the getParentIdByPlayerId + findById(parentId) TOCTOU gap and the
    // findById(null) IllegalArgumentException self-registered adult players (parentId == null) used
    // to hit; a missing/no-parent/deleted-parent player all fall through to no row here alike.
    @Transactional(readOnly = true)
    public String getParentEmailByPlayerId(Long playerId) {
        return playerProfileRepository.findParentEmailByPlayerId(playerId).orElse(null);
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
