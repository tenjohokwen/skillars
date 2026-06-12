package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Slf4j
@Component("playerOwnershipGuard")
@RequiredArgsConstructor
public class PlayerOwnershipGuard {

    private final PlayerProfileRepository playerProfileRepository;

    public boolean check(Authentication authentication, Long playerId) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof Principal skillarsP)) {
            return false;
        }
        try {
            Long parentId = Long.parseLong(skillarsP.getBusinessId());
            return playerProfileRepository.findByIdAndParentId(playerId, parentId).isPresent();
        } catch (NumberFormatException e) {
            log.warn("Ownership guard denied: malformed businessId in principal for playerId={}", playerId);
            return false;
        }
    }
}
