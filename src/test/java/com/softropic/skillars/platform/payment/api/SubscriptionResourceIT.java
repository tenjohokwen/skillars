package com.softropic.skillars.platform.payment.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.contract.CoachSubscriptionResponse;
import com.softropic.skillars.platform.payment.contract.PlayerSubscriptionResponse;
import com.softropic.skillars.platform.payment.contract.TierInfoResponse;
import com.softropic.skillars.platform.payment.service.SubscriptionService;
import com.softropic.skillars.platform.security.infrastructure.jwt.JwtSecretService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.video.service.VideoMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// AC3: 9 of SubscriptionResource's 10 endpoints have no HTTP-level coverage today — only
// GET /player/me is covered, by PlayerSubscriptionOwnershipIT. Reuses that class's
// TestSecurityConfig (anyRequest().authenticated(), with @EnableMethodSecurity) rather than
// duplicating it, per this codebase's existing precedent.
@WebMvcTest(SubscriptionResource.class)
@Import(PlayerSubscriptionOwnershipIT.TestSecurityConfig.class)
class SubscriptionResourceIT {

    private static final Long COACH_USER_ID = 7001L;
    private static final UUID COACH_PROFILE_ID = UUID.randomUUID();
    private static final Long PARENT_ID = 5001L;
    private static final Long PLAYER_ID = 123L;

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

    private void stubCoachIdentity() {
        CoachProfile coach = new CoachProfile();
        coach.setId(COACH_PROFILE_ID);
        coach.setUserId(COACH_USER_ID);
        when(securityUtil.getCurrentCoachUserId()).thenReturn(COACH_USER_ID);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
    }

    private void stubParentIdentity() {
        when(securityUtil.requireCurrentUserId()).thenReturn(PARENT_ID);
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
        stubParentIdentity();
        when(subscriptionService.subscribePlayer(PARENT_ID, PLAYER_ID, "SEMI_PRO", "YEARLY")).thenReturn(
            new PlayerSubscriptionResponse(UUID.randomUUID(), PLAYER_ID, "SEMI_PRO", "YEARLY", "ACTIVE",
                Instant.now().plusSeconds(86400), false));

        mockMvc.perform(post("/api/payment/subscriptions/player/subscribe")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(
                    java.util.Map.of("playerId", PLAYER_ID, "tier", "SEMI_PRO", "billingInterval", "YEARLY"))))
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
        stubParentIdentity();

        mockMvc.perform(post("/api/payment/subscriptions/player/change-tier")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(
                    java.util.Map.of("playerId", PLAYER_ID, "newTier", "PRO"))))
            .andExpect(status().isNoContent());
    }

    // ─── DELETE /player ─────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "PARENT")
    void cancelPlayerSubscription_happyPath_returns204() throws Exception {
        stubParentIdentity();

        mockMvc.perform(delete("/api/payment/subscriptions/player")
                .param("playerId", String.valueOf(PLAYER_ID)))
            .andExpect(status().isNoContent());
    }
}
