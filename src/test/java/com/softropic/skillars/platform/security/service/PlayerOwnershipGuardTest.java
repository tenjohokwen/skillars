package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-147: PlayerOwnershipGuard.check previously only ever checked parent ownership
 * (existsByIdAndParentId), so a self-registered adult player (parentId null, chk_pp_owner) was
 * unconditionally denied every resource gated on this guard — a real production bug found via
 * manual testing of the new Player Development Dashboard nav link, not inferred.
 */
@ExtendWith(MockitoExtension.class)
class PlayerOwnershipGuardTest {

    @Mock private PlayerProfileRepository playerProfileRepository;

    private PlayerOwnershipGuard guard;

    @BeforeEach
    void setUp() {
        guard = new PlayerOwnershipGuard(playerProfileRepository);
    }

    private Authentication authenticatedAs(String businessId) {
        Principal principal = new Principal.Builder()
            .username("user@example.com")
            .password("password")
            .enabled(true)
            .businessId(businessId)
            .otpEnabled(false)
            .build();
        return new UsernamePasswordAuthenticationToken(
            principal, principal.getPassword(), principal.getAuthorities());
    }

    @Test
    void check_parentOwnedPlayer_true() {
        when(playerProfileRepository.existsByIdAndParentId(42L, 7L)).thenReturn(true);

        assertThat(guard.check(authenticatedAs("7"), 42L)).isTrue();
    }

    @Test
    void check_selfRegisteredPlayer_true() {
        // The regression this test pins: parentId lookup correctly misses (parentId is null on a
        // self-registered profile), but the self-owned branch must still grant access.
        when(playerProfileRepository.existsByIdAndParentId(42L, 7L)).thenReturn(false);
        when(playerProfileRepository.existsByIdAndUserId(42L, 7L)).thenReturn(true);

        assertThat(guard.check(authenticatedAs("7"), 42L)).isTrue();
        // Mutation: drop the `|| existsByIdAndUserId(...)` disjunct → this goes RED, reproducing the
        // exact 403 the manual test hit on GET /api/development/players/{id}/timeline.
    }

    @Test
    void check_neitherParentNorSelf_false() {
        when(playerProfileRepository.existsByIdAndParentId(42L, 7L)).thenReturn(false);
        when(playerProfileRepository.existsByIdAndUserId(42L, 7L)).thenReturn(false);

        assertThat(guard.check(authenticatedAs("7"), 42L)).isFalse();
    }

    @Test
    void check_unauthenticated_false() {
        Authentication auth = new UsernamePasswordAuthenticationToken("x", "y");
        auth.setAuthenticated(false);

        assertThat(guard.check(auth, 42L)).isFalse();
    }

    @Test
    void check_nullAuthentication_false() {
        assertThat(guard.check(null, 42L)).isFalse();
    }

    @Test
    void check_malformedBusinessId_false() {
        assertThat(guard.check(authenticatedAs("not-a-number"), 42L)).isFalse();
    }
}
