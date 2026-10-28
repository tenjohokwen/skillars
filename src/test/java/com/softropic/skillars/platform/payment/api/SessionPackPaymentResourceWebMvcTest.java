package com.softropic.skillars.platform.payment.api;

import com.softropic.skillars.platform.booking.contract.PauseConflictResponse;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.config.PaymentProperties;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.contract.SavedPaymentMethodResponse;
import com.softropic.skillars.platform.payment.contract.SessionPackPurchaseResponse;
import com.softropic.skillars.platform.payment.repo.StripeCustomer;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.payment.service.PackSessionService;
import com.softropic.skillars.platform.payment.service.SessionPackPaymentService;
import com.softropic.skillars.platform.security.infrastructure.jwt.JwtSecretService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.video.service.VideoMetrics;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deferred-11 AC 1 / AC 4: publishable-key config endpoint and saved-payment-method endpoint.
 */
@WebMvcTest({SessionPackPaymentResource.class, PaymentApiAdvice.class})
@Import(SessionPackPaymentResourceWebMvcTest.TestSecurityConfig.class)
class SessionPackPaymentResourceWebMvcTest {

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

    private static final Long PARENT_USER_ID = 42L;

    @Autowired MockMvc mockMvc;

    @MockitoBean SessionPackPaymentService sessionPackPaymentService;
    @MockitoBean PackSessionService packSessionService;
    @MockitoBean StripeCustomerRepository stripeCustomerRepository;
    @MockitoBean PaymentGateway paymentGateway;
    @MockitoBean CoachProfileRepository coachProfileRepository;
    @MockitoBean SecurityUtil securityUtil;
    @MockitoBean PaymentProperties paymentProperties;
    @MockitoBean JwtSecretService jwtSecretService;
    @MockitoBean VideoMetrics videoMetrics;
    @MockitoBean com.softropic.skillars.platform.payment.service.CoachSoldPackQueryService coachSoldPackQueryService;

    // ─── GET /api/payment/coaches/me/session-packs (feat/coach-workflow-gaps A20) ────────────

    @Test
    @WithMockUser(roles = "COACH")
    void getMySoldPacks_coach_returnsCallersPacksWithClampedPageSize() throws Exception {
        java.util.UUID coachId = java.util.UUID.randomUUID();
        com.softropic.skillars.platform.marketplace.repo.CoachProfile coach =
            new com.softropic.skillars.platform.marketplace.repo.CoachProfile();
        coach.setId(coachId);
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        when(coachProfileRepository.findByUserId(PARENT_USER_ID)).thenReturn(java.util.Optional.of(coach));
        com.softropic.skillars.platform.payment.contract.CoachSoldPackDto dto =
            new com.softropic.skillars.platform.payment.contract.CoachSoldPackDto(
                java.util.UUID.randomUUID(), "Pat Parent", 7L, "Kid", "5 pack", 5, 2,
                java.time.Instant.parse("2026-11-01T00:00:00Z"), null, java.time.Instant.parse("2026-09-01T00:00:00Z"),
                "ACTIVE", true, true);
        when(coachSoldPackQueryService.getSoldPacks(org.mockito.ArgumentMatchers.eq(coachId), org.mockito.ArgumentMatchers.any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(dto)));

        mockMvc.perform(get("/api/payment/coaches/me/session-packs").param("size", "500"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].buyerName").value("Pat Parent"))
            .andExpect(jsonPath("$.content[0].extendable").value(true));

        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> pageable =
            org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        org.mockito.Mockito.verify(coachSoldPackQueryService).getSoldPacks(org.mockito.ArgumentMatchers.eq(coachId), pageable.capture());
        org.assertj.core.api.Assertions.assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    @WithMockUser(roles = "PARENT")
    void getMySoldPacks_parent_returns403() throws Exception {
        mockMvc.perform(get("/api/payment/coaches/me/session-packs"))
            .andExpect(status().isForbidden());
    }

    // ─── GET /api/payment/stripe/config ────────────────────────────────────────

    @Test
    @WithMockUser(roles = "PARENT")
    void getStripeConfig_authenticated_returns200WithPublishableKey() throws Exception {
        when(paymentProperties.getPublishableKey()).thenReturn("pk_test_abc123");

        mockMvc.perform(get("/api/payment/stripe/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.publishableKey").value("pk_test_abc123"));
    }

    @Test
    @WithAnonymousUser
    void getStripeConfig_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/payment/stripe/config"))
            .andExpect(status().isUnauthorized());
    }

    // ─── GET /api/payment/payment-method ───────────────────────────────────────

    @Test
    @WithMockUser(roles = "PARENT")
    void getSavedPaymentMethod_noCard_returns200WithHasCardFalse() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        when(sessionPackPaymentService.getSavedPaymentMethod(PARENT_USER_ID))
            .thenReturn(new SavedPaymentMethodResponse(false, null, null, null, null));

        mockMvc.perform(get("/api/payment/payment-method"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasCard").value(false))
            .andExpect(jsonPath("$.brand").doesNotExist())
            .andExpect(jsonPath("$.last4").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "PARENT")
    void getSavedPaymentMethod_hasCard_returns200WithCardDetails() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        when(sessionPackPaymentService.getSavedPaymentMethod(PARENT_USER_ID))
            .thenReturn(new SavedPaymentMethodResponse(true, "visa", "4242", 12L, 2030L));

        mockMvc.perform(get("/api/payment/payment-method"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasCard").value(true))
            .andExpect(jsonPath("$.brand").value("visa"))
            .andExpect(jsonPath("$.last4").value("4242"))
            .andExpect(jsonPath("$.expMonth").value(12))
            .andExpect(jsonPath("$.expYear").value(2030));
    }

    @Test
    @WithAnonymousUser
    void getSavedPaymentMethod_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/payment/payment-method"))
            .andExpect(status().isUnauthorized());
    }

    // ─── UAT.5 AC2: a self-registered PLAYER can save a card and pay per-session ────────────────
    //
    // Mutation check: reverting the @PreAuthorize widening on any one of these three endpoints
    // back to HAS_PARENT_ROLE turns each of these into a 403, failing the test.

    @Test
    @WithMockUser(roles = "PLAYER")
    void createSetupIntent_playerRole_returns200() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        StripeCustomer customer = new StripeCustomer();
        customer.setParentId(PARENT_USER_ID);
        customer.setStripeCustomerId("cus_player_test");
        when(stripeCustomerRepository.findById(PARENT_USER_ID)).thenReturn(Optional.of(customer));
        when(paymentGateway.createSetupIntent("cus_player_test")).thenReturn("seti_player_secret");

        mockMvc.perform(post("/api/payment/setup-intent").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.clientSecret").value("seti_player_secret"));
    }

    @Test
    @WithMockUser(roles = "PLAYER")
    void savePaymentMethod_playerRole_returns204() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        StripeCustomer customer = new StripeCustomer();
        customer.setParentId(PARENT_USER_ID);
        customer.setStripeCustomerId("cus_player_test");
        when(stripeCustomerRepository.findById(PARENT_USER_ID)).thenReturn(Optional.of(customer));

        mockMvc.perform(post("/api/payment/save-payment-method")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentMethodId\":\"pm_player_test\"}"))
            .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "PLAYER")
    void getSavedPaymentMethod_playerRole_returns200() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        when(sessionPackPaymentService.getSavedPaymentMethod(PARENT_USER_ID))
            .thenReturn(new SavedPaymentMethodResponse(false, null, null, null, null));

        mockMvc.perform(get("/api/payment/payment-method"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasCard").value(false));
    }

    // ─── UAT.6 AC1: a coach can save a card via the same generic endpoints ──────────────────────
    //
    // Mutation check: reverting the @PreAuthorize widening on any one of these three endpoints
    // back to HAS_PARENT_OR_PLAYER_ROLE turns each of these into a 403, failing the test.

    @Test
    @WithMockUser(roles = "COACH")
    void createSetupIntent_coachRole_returns200() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        StripeCustomer customer = new StripeCustomer();
        customer.setParentId(PARENT_USER_ID);
        customer.setStripeCustomerId("cus_coach_test");
        when(stripeCustomerRepository.findById(PARENT_USER_ID)).thenReturn(Optional.of(customer));
        when(paymentGateway.createSetupIntent("cus_coach_test")).thenReturn("seti_coach_secret");

        mockMvc.perform(post("/api/payment/setup-intent").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.clientSecret").value("seti_coach_secret"));
    }

    @Test
    @WithMockUser(roles = "COACH")
    void savePaymentMethod_coachRole_returns204() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        StripeCustomer customer = new StripeCustomer();
        customer.setParentId(PARENT_USER_ID);
        customer.setStripeCustomerId("cus_coach_test");
        when(stripeCustomerRepository.findById(PARENT_USER_ID)).thenReturn(Optional.of(customer));

        mockMvc.perform(post("/api/payment/save-payment-method")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentMethodId\":\"pm_coach_test\"}"))
            .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "COACH")
    void getSavedPaymentMethod_coachRole_returns200() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        when(sessionPackPaymentService.getSavedPaymentMethod(PARENT_USER_ID))
            .thenReturn(new SavedPaymentMethodResponse(false, null, null, null, null));

        mockMvc.perform(get("/api/payment/payment-method"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasCard").value(false));
    }

    // ─── Deferred-81 AC4: self-registered PLAYER can now purchase/manage/browse session packs ───
    //
    // Reverses the prior UAT.5-era decision (packs were explicitly out of scope for a self-booking
    // player) — the project owner decided during this story's creation to add pack-purchase parity
    // for self-booking players. Mutation check: reverting the @PreAuthorize widening on any one of
    // these four endpoints back to HAS_PARENT_ROLE turns each of these into a 403, failing the test.

    @Test
    @WithMockUser(roles = "PLAYER")
    void purchaseSessionPack_playerRole_returns201() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        java.util.UUID packTierId = java.util.UUID.randomUUID();
        when(sessionPackPaymentService.purchasePack(eq(PARENT_USER_ID), eq(packTierId), eq(1L), any(),
                org.mockito.ArgumentMatchers.isNull()))
            .thenReturn(new SessionPackPurchaseResponse(
                java.util.UUID.randomUUID(), packTierId, "Starter", 5, 5, new BigDecimal("20.00"),
                Instant.now().plusSeconds(3600), "pi_test", 1L, java.util.UUID.randomUUID(), null, "ACTIVE"));

        mockMvc.perform(post("/api/payment/session-packs/purchase")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"packTierId\":\"" + packTierId + "\",\"playerId\":1}"))
            .andExpect(status().isCreated());
    }

    /** fix/payment-session-pack-integrity: the client's per-attempt Idempotency-Key reaches the service. */
    @Test
    @WithMockUser(roles = "PARENT")
    void purchaseSessionPack_forwardsTheIdempotencyKeyHeader() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        java.util.UUID packTierId = java.util.UUID.randomUUID();

        mockMvc.perform(post("/api/payment/session-packs/purchase")
                .header("Idempotency-Key", "3f2b8c1e-attempt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"packTierId\":\"" + packTierId + "\",\"playerId\":1}"))
            .andExpect(status().isCreated());

        org.mockito.Mockito.verify(sessionPackPaymentService).purchasePack(
            eq(PARENT_USER_ID), eq(packTierId), eq(1L), any(), eq("3f2b8c1e-attempt"));
    }

    @Test
    @WithMockUser(roles = "PLAYER")
    void getMySessionPacks_playerRole_returns200() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        when(sessionPackPaymentService.getPacksForParent(PARENT_USER_ID, null)).thenReturn(List.of());

        mockMvc.perform(get("/api/payment/session-packs"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "PLAYER")
    void pauseSessionPack_playerRole_returns200() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        java.util.UUID purchaseId = java.util.UUID.randomUUID();
        when(packSessionService.pausePack(eq(PARENT_USER_ID), eq(purchaseId), any()))
            .thenReturn(new PauseConflictResponse(true, List.of(), Instant.now().plusSeconds(3600)));

        mockMvc.perform(post("/api/payment/session-packs/" + purchaseId + "/pause")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pauseStartDate\":\"" + Instant.now().plusSeconds(60) + "\",\"pauseDurationDays\":7}"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "PLAYER")
    void getActiveCoachTier_playerRole_returns404WhenNoActiveTier() throws Exception {
        java.util.UUID coachId = java.util.UUID.randomUUID();
        when(sessionPackPaymentService.getActiveCoachTier(coachId)).thenReturn(null);

        mockMvc.perform(get("/api/payment/coaches/" + coachId + "/session-pack-tiers"))
            .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "PLAYER")
    void getActiveCoachTier_playerRole_returns200WithTierWhenActive() throws Exception {
        java.util.UUID coachId = java.util.UUID.randomUUID();
        java.util.UUID tierId = java.util.UUID.randomUUID();
        com.softropic.skillars.platform.payment.contract.SessionPackTierResponse tier =
            new com.softropic.skillars.platform.payment.contract.SessionPackTierResponse(
                tierId, coachId, "Basic", 5, BigDecimal.valueOf(49.99), BigDecimal.valueOf(9.99), true, Instant.now());
        when(sessionPackPaymentService.getActiveCoachTier(coachId)).thenReturn(tier);

        mockMvc.perform(get("/api/payment/coaches/" + coachId + "/session-pack-tiers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.label").value("Basic"))
            .andExpect(jsonPath("$.sessionCount").value(5));
    }
}
