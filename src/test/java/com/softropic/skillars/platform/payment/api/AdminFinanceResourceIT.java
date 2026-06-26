package com.softropic.skillars.platform.payment.api;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.payment.contract.AdminFinanceOverviewDto;
import com.softropic.skillars.platform.payment.contract.CoachRevenueAdminDto;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for AdminFinanceResource HTTP endpoints.
 */
@WebMvcTest({AdminFinanceResource.class, PaymentApiAdvice.class})
@Import(AdminFinanceResourceIT.TestSecurityConfig.class)
class AdminFinanceResourceIT {

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

    private static final AdminFinanceOverviewDto ZERO_OVERVIEW = new AdminFinanceOverviewDto(
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, Map.of(), Map.of(), BigDecimal.ZERO
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
    void overview_adminRole_returns200WithSubscriptionRevenueZero() throws Exception {
        when(revenueReportingService.getAdminOverview(any(), any())).thenReturn(ZERO_OVERVIEW);

        mockMvc.perform(get("/api/admin/payment/overview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.subscriptionRevenue").value(0));
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
    void coachRevenue_knownCoach_outstandingDisputeCountIsZero() throws Exception {
        UUID coachId = UUID.randomUUID();
        CoachRevenueAdminDto dto = new CoachRevenueAdminDto(
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            0L, BigDecimal.ZERO, "EUR", 0, 0
        );
        when(revenueReportingService.getAdminCoachRevenue(eq(coachId), any(), any())).thenReturn(dto);

        mockMvc.perform(get("/api/admin/payment/coaches/{coachId}/revenue", coachId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outstandingDisputeCount").value(0));
    }
}
