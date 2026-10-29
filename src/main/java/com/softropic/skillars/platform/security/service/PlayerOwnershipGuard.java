package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.contract.util.AuthoritiesConstants;
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
     * fix/player-wallet-cashout: whether the caller may use the credit wallet (balance, statement, batch quote,
     * cash-out). The wallet is keyed by the paying user's own id — the caller's — so the question is only
     * whether the caller is a payer: a PARENT, or a self-registered adult PLAYER (own profile, no parent), whose
     * bookings and packs carry their own user id as the payer (UAT.5 opaque-payer model), so their refunds land
     * in this wallet. A PLAYER login without such a profile — any player belonging to a parent — is refused.
     */
    public boolean isWalletOwner(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof Principal skillarsP)) {
            return false;
        }
        boolean parent = hasAuthority(authentication, AuthoritiesConstants.PARENT);
        boolean player = hasAuthority(authentication, AuthoritiesConstants.PLAYER);
        if (parent) {
            return true;
        }
        if (!player) {
            return false;
        }
        try {
            return playerProfileRepository.existsByUserIdAndParentIdIsNull(Long.parseLong(skillarsP.getBusinessId()));
        } catch (NumberFormatException e) {
            log.warn("Wallet guard denied: malformed businessId in principal");
            return false;
        }
    }

    private static boolean hasAuthority(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
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
