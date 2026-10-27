package com.softropic.skillars.platform.payment.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.contract.CoachSubscriptionResponse;
import com.softropic.skillars.platform.payment.contract.PlayerSubscriptionResponse;
import com.softropic.skillars.platform.payment.contract.TierInfoResponse;
import com.softropic.skillars.platform.payment.service.SubscriptionService;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.infrastructure.jwt.JwtSecretService;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.service.PlayerOwnershipGuard;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.video.service.VideoMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// AC3: 9 of SubscriptionResource's 10 endpoints have no HTTP-level coverage today — only
// GET /player/me is covered, by PlayerSubscriptionOwnershipWebMvcTest. Reuses that class's
// TestSecurityConfig (anyRequest().authenticated(), with @EnableMethodSecurity) rather than
// duplicating it, per this codebase's existing precedent.
@WebMvcTest(SubscriptionResource.class)
@Import({PlayerSubscriptionOwnershipWebMvcTest.TestSecurityConfig.class, PlayerOwnershipGuard.class})
class SubscriptionResourceWebMvcTest {

    private static final Long COACH_USER_ID = 7001L;
    private static final UUID COACH_PROFILE_ID = UUID.randomUUID();
    private static final Long PARENT_ID = 5001L;
    private static final Long PLAYER_ID = 123L;
    private static final Long SELF_PLAYER_USER_ID = 8001L;
    private static final Long OTHER_PLAYER_ID = 999L;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean SubscriptionService subscriptionService;
    @MockitoBean CoachProfileRepository coachProfileRepository;
    @MockitoBean SecurityUtil securityUtil;
    // Not used by SubscriptionResource itself — @WebMvcTest instantiates every @Component/
    // @RestControllerAdvice on the classpath, and SecurityAdviceFilter (a @Component filter) requires
    // a JwtSecretService constructor argument, so this slice fails to boot without it.
    @MockitoBean JwtSecretService jwtSecretService;
    // Same reason as jwtSecretService above: VideoApiAdvice (@RestControllerAdvice) requires a
    // VideoMetrics constructor argument and is picked up by every @WebMvcTest slice regardless of module.
    @MockitoBean VideoMetrics videoMetrics;
    // The player-subscription mutations are guarded by the real PlayerOwnershipGuard (imported above).
    @MockitoBean PlayerProfileRepository playerProfileRepository;

    private void stubCoachIdentity() {
        CoachProfile coach = new CoachProfile();
        coach.setId(COACH_PROFILE_ID);
        coach.setUserId(COACH_USER_ID);
        when(securityUtil.getCurrentCoachUserId()).thenReturn(COACH_USER_ID);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
    }

    /**
     * A real {@link Principal}-backed authentication — PlayerOwnershipGuard reads businessId off the
     * principal, which {@code @WithMockUser}'s Spring User does not carry.
     */
    private RequestPostProcessor caller(String role, Long userId) {
        Principal principal = mock(Principal.class);
        when(principal.getBusinessId()).thenReturn(String.valueOf(userId));
        when(securityUtil.requireCurrentUserId()).thenReturn(userId);
        return authentication(new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority(role))));
    }

    private RequestPostProcessor parentOfPlayer() {
        when(playerProfileRepository.existsByIdAndParentId(PLAYER_ID, PARENT_ID)).thenReturn(true);
        return caller("ROLE_PARENT", PARENT_ID);
    }

    private RequestPostProcessor selfRegisteredPlayerOwning(Long playerId) {
        when(playerProfileRepository.existsByIdAndUserId(playerId, SELF_PLAYER_USER_ID)).thenReturn(true);
        return caller("ROLE_PLAYER", SELF_PLAYER_USER_ID);
    }

    // ─── GET /coach/tiers — permitAll(), but this test's own filter chain still requires auth ──

    @Test
    @WithMockUser(roles = "PARENT")
    void getCoachTiers_authenticated_returns200() throws Exception {
        when(subscriptionService.getCoachTiers()).thenReturn(
            List.of(new TierInfoResponse("PRO", List.of("feature-a"), "10.00", "100.00")));

        mockMvc.perform(get("/api/payment/subscriptions/coach/tiers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].tier").value("PRO"));
    }

    @Test
    void getCoachTiers_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/payment/subscriptions/coach/tiers"))
            .andExpect(status().isUnauthorized());
    }

    // ─── GET /coach/me ──────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "COACH")
    void getMyCoachSubscription_happyPath_returns200() throws Exception {
        stubCoachIdentity();
        when(subscriptionService.getCoachSubscription(COACH_PROFILE_ID)).thenReturn(
            new CoachSubscriptionResponse(UUID.randomUUID(), "PRO", "ACTIVE", Instant.now().plusSeconds(86400), false));

        mockMvc.perform(get("/api/payment/subscriptions/coach/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tier").value("PRO"));
    }

    @Test
    @WithMockUser(roles = "PARENT")
    void getMyCoachSubscription_parentCaller_returns403() throws Exception {
        mockMvc.perform(get("/api/payment/subscriptions/coach/me"))
            .andExpect(status().isForbidden());
    }

    // ─── POST /coach/subscribe ──────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "COACH")
    void subscribeCoach_happyPath_returns200() throws Exception {
        stubCoachIdentity();
        when(subscriptionService.subscribeCoach(COACH_PROFILE_ID, COACH_USER_ID, "PRO")).thenReturn(
            new CoachSubscriptionResponse(UUID.randomUUID(), "PRO", "ACTIVE", Instant.now().plusSeconds(86400), false));

        mockMvc.perform(post("/api/payment/subscriptions/coach/subscribe")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(java.util.Map.of("tier", "PRO"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tier").value("PRO"));
    }

    // ─── POST /coach/change-tier ────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "COACH")
    void changeCoachTier_happyPath_returns204() throws Exception {
        stubCoachIdentity();

        mockMvc.perform(post("/api/payment/subscriptions/coach/change-tier")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(java.util.Map.of("newTier", "ELITE"))))
            .andExpect(status().isNoContent());
    }

    // ─── DELETE /coach ──────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "COACH")
    void cancelCoachSubscription_happyPath_returns204() throws Exception {
        stubCoachIdentity();

        mockMvc.perform(delete("/api/payment/subscriptions/coach"))
            .andExpect(status().isNoContent());
    }

    // ─── GET /player/tiers — permitAll(), but this test's own filter chain still requires auth ──

    @Test
    @WithMockUser(roles = "COACH")
    void getPlayerTiers_authenticated_returns200() throws Exception {
        when(subscriptionService.getPlayerTiers()).thenReturn(
            List.of(new TierInfoResponse("SEMI_PRO", List.of("feature-b"), "5.00", "50.00")));

        mockMvc.perform(get("/api/payment/subscriptions/player/tiers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].tier").value("SEMI_PRO"));
    }

    @Test
    void getPlayerTiers_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/payment/subscriptions/player/tiers"))
            .andExpect(status().isUnauthorized());
    }

    // ─── POST /player/subscribe ─────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "PARENT")
    void subscribePlayer_happyPath_returns200() throws Exception {
        RequestPostProcessor parent = parentOfPlayer();
        when(subscriptionService.subscribePlayer(PARENT_ID, PLAYER_ID, "SEMI_PRO", "YEARLY")).thenReturn(
            new PlayerSubscriptionResponse(UUID.randomUUID(), PLAYER_ID, "SEMI_PRO", "YEARLY", "ACTIVE",
                Instant.now().plusSeconds(86400), false));

        mockMvc.perform(post("/api/payment/subscriptions/player/subscribe")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(
                    java.util.Map.of("playerId", PLAYER_ID, "tier", "SEMI_PRO", "billingInterval", "YEARLY")))
                .with(parent))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.playerId").value(PLAYER_ID.intValue()))
            .andExpect(jsonPath("$.tier").value("SEMI_PRO"));
    }

    // AC3's cheapest proof that request validation is actually exercised in this slice — every
    // field on PlayerSubscribeRequest is @NotNull, so an empty body must 400.
    @Test
    @WithMockUser(roles = "PARENT")
    void subscribePlayer_emptyBody_returns400() throws Exception {
        mockMvc.perform(post("/api/payment/subscriptions/player/subscribe")
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    // ─── POST /player/change-tier ───────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "PARENT")
    void changePlayerTier_happyPath_returns204() throws Exception {
        mockMvc.perform(post("/api/payment/subscriptions/player/change-tier")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(
                    java.util.Map.of("playerId", PLAYER_ID, "newTier", "PRO")))
                .with(parentOfPlayer()))
            .andExpect(status().isNoContent());
        verify(subscriptionService).changePlayerTier(PARENT_ID, PLAYER_ID, "PRO");
    }

    // ─── DELETE /player ─────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "PARENT")
    void cancelPlayerSubscription_happyPath_returns204() throws Exception {
        mockMvc.perform(delete("/api/payment/subscriptions/player")
                .param("playerId", String.valueOf(PLAYER_ID))
                .with(parentOfPlayer()))
            .andExpect(status().isNoContent());
        verify(subscriptionService).cancelPlayerSubscription(PARENT_ID, PLAYER_ID);
    }

    // Self-registered adult players manage their OWN subscription (reverses skillars-deferred-150
    // AC5's HAS_PARENT_ROLE pin): the route + page already admitted them, but every mutation 403'd.
    // The guard still refuses any profile the caller does not own.

    @Test
    void subscribePlayer_selfRegisteredPlayerOwnProfile_returns200() throws Exception {
        when(subscriptionService.subscribePlayer(SELF_PLAYER_USER_ID, PLAYER_ID, "SEMI_PRO", "YEARLY")).thenReturn(
            new PlayerSubscriptionResponse(UUID.randomUUID(), PLAYER_ID, "SEMI_PRO", "YEARLY", "ACTIVE",
                Instant.now().plusSeconds(86400), false));

        mockMvc.perform(post("/api/payment/subscriptions/player/subscribe")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(
                    java.util.Map.of("playerId", PLAYER_ID, "tier", "SEMI_PRO", "billingInterval", "YEARLY")))
                .with(selfRegisteredPlayerOwning(PLAYER_ID)))
            .andExpect(status().isOk());
    }

    @Test
    void changePlayerTier_selfRegisteredPlayerOwnProfile_returns204() throws Exception {
        mockMvc.perform(post("/api/payment/subscriptions/player/change-tier")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(
                    java.util.Map.of("playerId", PLAYER_ID, "newTier", "PRO")))
                .with(selfRegisteredPlayerOwning(PLAYER_ID)))
            .andExpect(status().isNoContent());
        verify(subscriptionService).changePlayerTier(SELF_PLAYER_USER_ID, PLAYER_ID, "PRO");
    }

    @Test
    void cancelPlayerSubscription_selfRegisteredPlayerOwnProfile_returns204() throws Exception {
        mockMvc.perform(delete("/api/payment/subscriptions/player")
                .param("playerId", String.valueOf(PLAYER_ID))
                .with(selfRegisteredPlayerOwning(PLAYER_ID)))
            .andExpect(status().isNoContent());
        verify(subscriptionService).cancelPlayerSubscription(SELF_PLAYER_USER_ID, PLAYER_ID);
    }

    @Test
    void subscribePlayer_playerOnSomeoneElsesProfile_returns403() throws Exception {
        RequestPostProcessor player = selfRegisteredPlayerOwning(PLAYER_ID);
        mockMvc.perform(post("/api/payment/subscriptions/player/subscribe")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(
                    java.util.Map.of("playerId", OTHER_PLAYER_ID, "tier", "SEMI_PRO", "billingInterval", "YEARLY")))
                .with(player))
            .andExpect(status().isForbidden());
        verifyNoInteractions(subscriptionService);
    }

    @Test
    void cancelPlayerSubscription_parentOfAnotherPlayer_returns403() throws Exception {
        RequestPostProcessor parent = parentOfPlayer();
        mockMvc.perform(delete("/api/payment/subscriptions/player")
                .param("playerId", String.valueOf(OTHER_PLAYER_ID))
                .with(parent))
            .andExpect(status().isForbidden());
        verifyNoInteractions(subscriptionService);
    }

    @Test
    @WithMockUser(roles = "COACH")
    void changePlayerTier_coachCaller_returns403() throws Exception {
        mockMvc.perform(post("/api/payment/subscriptions/player/change-tier")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(
                    java.util.Map.of("playerId", PLAYER_ID, "newTier", "PRO"))))
            .andExpect(status().isForbidden());
    }
}
