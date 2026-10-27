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
            // businessId is the caller's own userId regardless of role (Principal.instanceFrom), so
            // the SAME id is checked both ways: as the parent on a parent-owned profile, and as the
            // user on a self-registered adult player's own profile (parentId null there, chk_pp_owner)
            // — skillars-deferred-147: the self-owned branch was missing entirely, so a self-registered
            // PLAYER was unconditionally denied every development/session/payment resource about
            // themselves, caught via manual testing of the new Player Development Dashboard nav link.
            return owns(Long.parseLong(skillarsP.getBusinessId()), playerId);
        } catch (NumberFormatException e) {
            log.warn("Ownership guard denied: malformed businessId in principal for playerId={}", playerId);
            return false;
        }
    }

    /**
     * Whether {@code callerUserId} owns player profile {@code playerId}: as the parent of a minor's
     * profile, or as the self-registered player whose own profile it is. For service-layer checks
     * where the player id is only known after loading a row (e.g. a homework assignment).
     */
    public boolean owns(Long callerUserId, Long playerId) {
        if (callerUserId == null || playerId == null) {
            return false;
        }
        return playerProfileRepository.existsByIdAndParentId(playerId, callerUserId)
            || playerProfileRepository.existsByIdAndUserId(playerId, callerUserId);
    }
}
