package com.softropic.skillars.platform.payment.api;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.payment.contract.AdminFinanceOverviewDto;
import com.softropic.skillars.platform.payment.contract.CoachRevenueAdminDto;
import com.softropic.skillars.platform.payment.service.CoachPayoutHoldRecoveryService;
import com.softropic.skillars.platform.payment.service.RevenueReportingService;
import com.softropic.skillars.platform.security.infrastructure.jwt.JwtSecretService;
import com.softropic.skillars.platform.video.service.VideoMetrics;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for AdminFinanceResource HTTP endpoints.
 */
@WebMvcTest({AdminFinanceResource.class, PaymentApiAdvice.class})
@Import(AdminFinanceResourceWebMvcTest.TestSecurityConfig.class)
class AdminFinanceResourceWebMvcTest {

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

    @Autowired MockMvc mockMvc;

    @MockitoBean RevenueReportingService revenueReportingService;
    @MockitoBean JwtSecretService jwtSecretService;
    @MockitoBean VideoMetrics videoMetrics;
    @MockitoBean CoachPayoutHoldRecoveryService coachPayoutHoldRecoveryService;

    private static final AdminFinanceOverviewDto ZERO_OVERVIEW = new AdminFinanceOverviewDto(
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, Map.of(), Map.of(), 0L, BigDecimal.ZERO
    );

    // ── Role enforcement ─────────────────────────────────────────

    @Test
    void overview_unauthenticated_returns401or403() throws Exception {
        mockMvc.perform(get("/api/admin/payment/overview"))
            .andExpect(status().is4xxClientError());
    }

    @Test
    @WithMockUser(roles = "COACH")
    void overview_coachRole_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/payment/overview"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void overview_adminRole_returns200_withoutAFakeSubscriptionRevenueFigure() throws Exception {
        when(revenueReportingService.getAdminOverview(any(), any())).thenReturn(ZERO_OVERVIEW);

        // fix/payment-money-integrity: subscriptionRevenue was a hard-coded 0 and is no longer sent.
        mockMvc.perform(get("/api/admin/payment/overview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalGrossVolume").value(0))
            .andExpect(jsonPath("$.subscriptionRevenue").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void overview_adminRole_returnsEmptySubscriptionMaps() throws Exception {
        when(revenueReportingService.getAdminOverview(any(), any())).thenReturn(ZERO_OVERVIEW);

        mockMvc.perform(get("/api/admin/payment/overview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activeCoachSubscriptions").isMap())
            .andExpect(jsonPath("$.activePlayerSubscriptions").isMap());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void coachRevenue_unknownCoach_returns404() throws Exception {
        UUID ghostCoach = UUID.randomUUID();
        when(revenueReportingService.getAdminCoachRevenue(eq(ghostCoach), any(), any()))
            .thenThrow(new ResourceNotFoundException("Coach not found", "coach_profile"));

        mockMvc.perform(get("/api/admin/payment/coaches/{coachId}/revenue", ghostCoach))
            .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void coachRevenue_knownCoach_omitsAlwaysZeroAndFeeFields() throws Exception {
        UUID coachId = UUID.randomUUID();
        CoachRevenueAdminDto dto = new CoachRevenueAdminDto(
            new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("90.00"),
            1L, BigDecimal.ZERO, "EUR", 0, BigDecimal.ZERO, 0L, BigDecimal.ZERO, 0L
        );
        when(revenueReportingService.getAdminCoachRevenue(eq(coachId), any(), any())).thenReturn(dto);

        // fix/payment-money-integrity: outstandingDisputeCount was a hard-coded 0 and stripeFees an
        // estimate of a fee the coach never pays — both removed; netPayout is the transferred net.
        mockMvc.perform(get("/api/admin/payment/coaches/{coachId}/revenue", coachId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.netPayout").value(90.00))
            .andExpect(jsonPath("$.outstandingDisputeCount").doesNotExist())
            .andExpect(jsonPath("$.stripeFees").doesNotExist());
    }
    // ── fix/payment-payout-recovery: admin re-drive of HOLD payouts ──

    @Test
    @WithMockUser(roles = "COACH")
    void releaseHeld_coachRole_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/payment/coaches/{coachId}/payouts/release-held", UUID.randomUUID()))
            .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(coachPayoutHoldRecoveryService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void releaseHeld_adminRole_redrivesWithoutRequiringReadiness() throws Exception {
        UUID coachId = UUID.randomUUID();
        when(coachPayoutHoldRecoveryService.redriveHeld(eq(coachId), eq(false), any()))
            .thenReturn(new CoachPayoutHoldRecoveryService.Result(2, 1, 0));

        mockMvc.perform(post("/api/admin/payment/coaches/{coachId}/payouts/release-held", coachId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.released").value(2))
            .andExpect(jsonPath("$.stillFrozen").value(1));
    }
}
