package com.softropic.skillars.platform.payment.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.payment.contract.CashOutRequest;
import com.softropic.skillars.platform.payment.service.CashOutService;
import com.softropic.skillars.platform.payment.service.CreditWalletService;
import com.softropic.skillars.platform.security.infrastructure.jwt.JwtSecretService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.video.service.VideoMetrics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import jakarta.servlet.http.HttpServletResponse;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// skillars-deferred-141 AC4.1a: GET /balance widened from HAS_PARENT_ROLE to
// HAS_PARENT_OR_PLAYER_ROLE so a self-registered adult player can read their own credit wallet
// for the new player dashboard. POST /cashout deliberately stays PARENT-only (not in scope).
@WebMvcTest({CreditWalletResource.class, PaymentApiAdvice.class})
@Import(CreditWalletResourceWebMvcTest.TestSecurityConfig.class)
class CreditWalletResourceWebMvcTest {

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

    private static final Long CALLER_ID = 9001L;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean CreditWalletService creditWalletService;
    @MockitoBean CashOutService cashOutService;
    @MockitoBean SecurityUtil securityUtil;
    @MockitoBean JwtSecretService jwtSecretService;
    @MockitoBean VideoMetrics videoMetrics;
    @MockitoBean com.softropic.skillars.platform.payment.service.BatchPaymentQuoteService batchPaymentQuoteService;

    // ─── GET /api/payment/credits/batch-quote (feat/coach-workflow-gaps A22) ──

    @Test
    @WithMockUser(roles = "PLAYER")
    void getBatchQuote_player_returnsCallersSplit() throws Exception {
        java.util.UUID coachId = java.util.UUID.randomUUID();
        when(securityUtil.requireCurrentUserId()).thenReturn(CALLER_ID);
        when(batchPaymentQuoteService.quote(CALLER_ID, coachId, 3)).thenReturn(
            new com.softropic.skillars.platform.payment.contract.BatchPaymentQuoteDto(
                new BigDecimal("40.00"), 3, new BigDecimal("120.00"), new BigDecimal("25.00"),
                new BigDecimal("25.00"), new BigDecimal("95.00"), "EUR"));

        mockMvc.perform(get("/api/payment/credits/batch-quote")
                .param("coachId", coachId.toString()).param("sessionCount", "3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.creditApplied").value(25.00))
            .andExpect(jsonPath("$.cardCharge").value(95.00))
            .andExpect(jsonPath("$.total").value(120.00));
    }

    @Test
    @WithMockUser(roles = "PARENT")
    void getBatchQuote_sessionCountAboveBatchMax_returns400() throws Exception {
        mockMvc.perform(get("/api/payment/credits/batch-quote")
                .param("coachId", java.util.UUID.randomUUID().toString()).param("sessionCount", "11"))
            .andExpect(status().is4xxClientError());
        org.mockito.Mockito.verifyNoInteractions(batchPaymentQuoteService);
    }

    @Test
    @WithMockUser(roles = "COACH")
    void getBatchQuote_coach_returns403() throws Exception {
        mockMvc.perform(get("/api/payment/credits/batch-quote")
                .param("coachId", java.util.UUID.randomUUID().toString()).param("sessionCount", "2"))
            .andExpect(status().isForbidden());
    }

    // ─── GET /api/payment/credits/balance ─────────────────────────────────────

    @Test
    @WithMockUser(roles = "PARENT")
    void getBalance_parentRole_returns200() throws Exception {
        when(securityUtil.requireCurrentUserId()).thenReturn(CALLER_ID);
        when(creditWalletService.getBalance(CALLER_ID)).thenReturn(new BigDecimal("12.50"));

        mockMvc.perform(get("/api/payment/credits/balance"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").value(12.50))
            .andExpect(jsonPath("$.currency").value("EUR"));
    }

    @Test
    @WithMockUser(roles = "PLAYER")
    void getBalance_playerRole_returns200() throws Exception {
        when(securityUtil.requireCurrentUserId()).thenReturn(CALLER_ID);
        when(creditWalletService.getBalance(CALLER_ID)).thenReturn(new BigDecimal("3.00"));

        mockMvc.perform(get("/api/payment/credits/balance"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").value(3.00))
            .andExpect(jsonPath("$.currency").value("EUR"));
    }

    @Test
    @WithMockUser(roles = "COACH")
    void getBalance_coachRole_returns403() throws Exception {
        mockMvc.perform(get("/api/payment/credits/balance"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void getBalance_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/payment/credits/balance"))
            .andExpect(status().isUnauthorized());
    }

    // ─── POST /api/payment/credits/cashout — unchanged, still PARENT-only ─────

    @Test
    @WithMockUser(roles = "PLAYER")
    void cashOut_playerRole_returns403() throws Exception {
        mockMvc.perform(post("/api/payment/credits/cashout")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new CashOutRequest(new BigDecimal("5.00")))))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "PARENT")
    void cashOut_parentRole_returns204() throws Exception {
        when(securityUtil.requireCurrentUserId()).thenReturn(CALLER_ID);

        mockMvc.perform(post("/api/payment/credits/cashout")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new CashOutRequest(new BigDecimal("5.00")))))
            .andExpect(status().isNoContent());
    }
}
