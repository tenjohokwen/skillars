package com.softropic.skillars.platform.payment.api;

import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.contract.PlayerSubscriptionResponse;
import com.softropic.skillars.platform.payment.service.SubscriptionService;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.infrastructure.jwt.JwtSecretService;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.service.PlayerOwnershipGuard;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.video.service.VideoMetrics;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SubscriptionResource.class)
@Import({PlayerSubscriptionOwnershipIT.TestSecurityConfig.class, PlayerOwnershipGuard.class})
class PlayerSubscriptionOwnershipIT {

    @TestConfiguration
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
            return http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(
                    (req, res, ex) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
                .build();
        }
    }

    private static final long PARENT_ID  = 5001L;
    private static final long OWN_PLAYER = 123L;
    private static final long OTHER_PLAYER = 999L;

    private static final PlayerSubscriptionResponse STUB_RESPONSE = new PlayerSubscriptionResponse(
        UUID.randomUUID(), OWN_PLAYER, "SEMI_PRO", "YEARLY", "ACTIVE",
        Instant.now().plusSeconds(86400), false
    );

    @Autowired MockMvc mockMvc;

    @MockitoBean SubscriptionService subscriptionService;
    @MockitoBean CoachProfileRepository coachProfileRepository;
    @MockitoBean SecurityUtil securityUtil;
    @MockitoBean JwtSecretService jwtSecretService;
    @MockitoBean PlayerProfileRepository playerProfileRepository;
    @MockitoBean VideoMetrics videoMetrics;

    // ─── 200: parent accesses their own player ────────────────────────────────

    @Test
    void getPlayerSubscription_ownPlayer_returns200() throws Exception {
        // PlayerOwnershipGuard checks authentication.getPrincipal() instanceof Principal —
        // @WithMockUser injects a Spring User, not our Principal. Provide real auth instead.
        com.softropic.skillars.platform.security.contract.Principal principal = mock(com.softropic.skillars.platform.security.contract.Principal.class);
        when(principal.getBusinessId()).thenReturn(String.valueOf(PARENT_ID));
        when(securityUtil.getCurrentUser()).thenReturn(principal);

        Authentication auth = new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority("ROLE_PARENT"))
        );

        stubPlayerOwnedBy(OWN_PLAYER, PARENT_ID, true);
        when(subscriptionService.getPlayerSubscription(PARENT_ID, OWN_PLAYER))
            .thenReturn(STUB_RESPONSE);

        mockMvc.perform(get("/api/payment/subscriptions/player/me")
                .param("playerId", String.valueOf(OWN_PLAYER))
                .with(authentication(auth)))
            .andExpect(status().isOk());
    }

    // ─── 403: parent tries to access another parent's player ──────────────────

    @Test
    @WithMockUser(roles = "PARENT")
    void getPlayerSubscription_otherPlayerId_returns403NotFound() throws Exception {
        stubParentPrincipal(PARENT_ID);
        stubPlayerOwnedBy(OTHER_PLAYER, PARENT_ID, false);

        mockMvc.perform(get("/api/payment/subscriptions/player/me")
                .param("playerId", String.valueOf(OTHER_PLAYER)))
            .andExpect(status().isForbidden());
    }

    // ─── 403: missing authentication ─────────────────────────────────────────

    @Test
    @WithMockUser(roles = "PARENT")
    void getPlayerSubscription_wrongPlayerId_returns403() throws Exception {
        stubParentPrincipal(PARENT_ID);
        stubPlayerOwnedBy(OWN_PLAYER + 1, PARENT_ID, false);

        mockMvc.perform(get("/api/payment/subscriptions/player/me")
                .param("playerId", String.valueOf(OWN_PLAYER + 1)))
            .andExpect(status().isForbidden());
    }

    // ─── 401: unauthenticated request ────────────────────────────────────────

    @Test
    void getPlayerSubscription_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/payment/subscriptions/player/me")
                .param("playerId", String.valueOf(OWN_PLAYER)))
            .andExpect(status().isUnauthorized());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void stubParentPrincipal(long parentId) {
        Principal principal = mock(Principal.class);
        when(principal.getBusinessId()).thenReturn(String.valueOf(parentId));
        when(securityUtil.getCurrentUser()).thenReturn(principal);
    }

    private void stubPlayerOwnedBy(long playerId, long parentId, boolean owned) {
        if (owned) {
            PlayerProfile profile = new PlayerProfile();
            profile.setId(playerId);
            when(playerProfileRepository.findByIdAndParentId(playerId, parentId))
                .thenReturn(Optional.of(profile));
        } else {
            when(playerProfileRepository.findByIdAndParentId(playerId, parentId))
                .thenReturn(Optional.empty());
        }
    }
}
