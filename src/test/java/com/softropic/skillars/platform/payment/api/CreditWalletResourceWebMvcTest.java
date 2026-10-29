package com.softropic.skillars.platform.payment.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.payment.contract.CashOutRequest;
import com.softropic.skillars.platform.payment.service.CashOutService;
import com.softropic.skillars.platform.payment.service.CreditWalletService;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.infrastructure.jwt.JwtSecretService;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.service.PlayerOwnershipGuard;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.video.service.VideoMetrics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import jakarta.servlet.http.HttpServletResponse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// skillars-deferred-141 AC4.1a: GET /balance widened from HAS_PARENT_ROLE to
// HAS_PARENT_OR_PLAYER_ROLE so a self-registered adult player can read their own credit wallet
// for the new player dashboard.
// fix/player-wallet-cashout: every wallet endpoint (cash-out included) is now gated on
// CreditWalletResource.WALLET_OWNER — a PARENT, or a PLAYER whose own profile has no parent — through the
// real PlayerOwnershipGuard bean (imported below; its repository is mocked).
@WebMvcTest({CreditWalletResource.class, PaymentApiAdvice.class})
@Import({CreditWalletResourceWebMvcTest.TestSecurityConfig.class, PlayerOwnershipGuard.class})
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
    private static final long PARENT_LINKED_PLAYER_USER_ID = 9002L;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean CreditWalletService creditWalletService;
    @MockitoBean CashOutService cashOutService;
    @MockitoBean SecurityUtil securityUtil;
    @MockitoBean JwtSecretService jwtSecretService;
    @MockitoBean VideoMetrics videoMetrics;
    @MockitoBean com.softropic.skillars.platform.payment.service.BatchPaymentQuoteService batchPaymentQuoteService;
    @MockitoBean PlayerProfileRepository playerProfileRepository;

    /**
     * PlayerOwnershipGuard reads the caller id from a real {@link Principal} (businessId) — @WithMockUser
     * injects a plain Spring User, which the guard refuses — so every allowed-path test authenticates as one.
     */
    private static Authentication as(long userId, String role) {
        Principal principal = new Principal.Builder()
            .username("user" + userId + "@example.com")
            .password("password")
            .enabled(true)
            .businessId(String.valueOf(userId))
            .otpEnabled(false)
            .build();
        return new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority(role)));
    }

    private static Authentication parent() {
        return as(CALLER_ID, "ROLE_PARENT");
    }

    /** A self-registered adult player: owns a profile with no parent. */
    private Authentication selfRegisteredPlayer() {
        when(playerProfileRepository.existsByUserIdAndParentIdIsNull(CALLER_ID)).thenReturn(true);
        return as(CALLER_ID, "ROLE_PLAYER");
    }

    private String cashOutBody() throws Exception {
        return objectMapper.writeValueAsString(new CashOutRequest(new BigDecimal("5.00")));
    }

    // ─── GET /api/payment/credits/batch-quote (feat/coach-workflow-gaps A22) ──

    @Test
    void getBatchQuote_player_returnsCallersSplit() throws Exception {
        java.util.UUID coachId = java.util.UUID.randomUUID();
        when(securityUtil.requireCurrentUserId()).thenReturn(CALLER_ID);
        when(batchPaymentQuoteService.quote(CALLER_ID, coachId, 3)).thenReturn(
            new com.softropic.skillars.platform.payment.contract.BatchPaymentQuoteDto(
                new BigDecimal("40.00"), 3, new BigDecimal("120.00"), new BigDecimal("25.00"),
                new BigDecimal("25.00"), new BigDecimal("95.00"), "EUR"));

        mockMvc.perform(get("/api/payment/credits/batch-quote")
                .param("coachId", coachId.toString()).param("sessionCount", "3")
                .with(authentication(selfRegisteredPlayer())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.creditApplied").value(25.00))
            .andExpect(jsonPath("$.cardCharge").value(95.00))
            .andExpect(jsonPath("$.total").value(120.00));
    }

    @Test
    void getBatchQuote_sessionCountAboveBatchMax_returns400() throws Exception {
        mockMvc.perform(get("/api/payment/credits/batch-quote")
                .param("coachId", java.util.UUID.randomUUID().toString()).param("sessionCount", "11")
                .with(authentication(parent())))
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
    void getBalance_parentRole_returns200() throws Exception {
        when(securityUtil.requireCurrentUserId()).thenReturn(CALLER_ID);
        // fix/payment-wallet-dispute-integrity: with the cashable / spend-only split.
        when(creditWalletService.getWalletBalance(CALLER_ID)).thenReturn(new CreditWalletService.WalletBalance(
            new BigDecimal("12.50"), new BigDecimal("10.00"), new BigDecimal("2.50")));

        mockMvc.perform(get("/api/payment/credits/balance").with(authentication(parent())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").value(12.50))
            .andExpect(jsonPath("$.cashableBalance").value(2.50))
            .andExpect(jsonPath("$.spendOnlyBalance").value(10.00))
            .andExpect(jsonPath("$.currency").value("EUR"));
    }

    @Test
    void getBalance_selfRegisteredPlayer_returns200() throws Exception {
        when(securityUtil.requireCurrentUserId()).thenReturn(CALLER_ID);
        when(creditWalletService.getWalletBalance(CALLER_ID)).thenReturn(new CreditWalletService.WalletBalance(
            new BigDecimal("3.00"), BigDecimal.ZERO, new BigDecimal("3.00")));

        mockMvc.perform(get("/api/payment/credits/balance").with(authentication(selfRegisteredPlayer())))
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

    @Test
    void getBalance_playerWithoutSelfOwnedProfile_returns403() throws Exception {
        // A PLAYER login that is not a self-registered adult (e.g. a profile belonging to a parent) has no wallet.
        when(playerProfileRepository.existsByUserIdAndParentIdIsNull(PARENT_LINKED_PLAYER_USER_ID)).thenReturn(false);

        mockMvc.perform(get("/api/payment/credits/balance")
                .with(authentication(as(PARENT_LINKED_PLAYER_USER_ID, "ROLE_PLAYER"))))
            .andExpect(status().isForbidden());
        verify(creditWalletService, never()).getWalletBalance(anyLong());
    }

    @Test
    @WithMockUser(roles = "PLAYER")
    void getBalance_playerWithoutSkillarsPrincipal_returns403() throws Exception {
        // The guard needs the caller's own id; a principal that is not a Skillars Principal is refused.
        mockMvc.perform(get("/api/payment/credits/balance"))
            .andExpect(status().isForbidden());
    }

    // ─── POST /api/payment/credits/cashout (fix/player-wallet-cashout: parent or self-registered player) ─

    @Test
    void cashOut_selfRegisteredPlayer_returns204_onTheirOwnWallet() throws Exception {
        when(securityUtil.requireCurrentUserId()).thenReturn(CALLER_ID);

        mockMvc.perform(post("/api/payment/credits/cashout")
                .contentType("application/json")
                .content(cashOutBody())
                .with(authentication(selfRegisteredPlayer())))
            .andExpect(status().isNoContent());
        verify(cashOutService).processCashOut(CALLER_ID, new BigDecimal("5.00"));
    }

    @Test
    void cashOut_playerWithoutSelfOwnedProfile_returns403() throws Exception {
        when(playerProfileRepository.existsByUserIdAndParentIdIsNull(PARENT_LINKED_PLAYER_USER_ID)).thenReturn(false);

        mockMvc.perform(post("/api/payment/credits/cashout")
                .contentType("application/json")
                .content(cashOutBody())
                .with(authentication(as(PARENT_LINKED_PLAYER_USER_ID, "ROLE_PLAYER"))))
            .andExpect(status().isForbidden());
        verify(cashOutService, never()).processCashOut(anyLong(), any());
    }

    @Test
    void cashOut_coachRole_returns403() throws Exception {
        mockMvc.perform(post("/api/payment/credits/cashout")
                .contentType("application/json")
                .content(cashOutBody())
                .with(authentication(as(CALLER_ID, "ROLE_COACH"))))
            .andExpect(status().isForbidden());
        verify(cashOutService, never()).processCashOut(anyLong(), any());
    }

    @Test
    @WithAnonymousUser
    void cashOut_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/payment/credits/cashout")
                .contentType("application/json")
                .content(cashOutBody()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void cashOut_parentRole_returns204() throws Exception {
        when(securityUtil.requireCurrentUserId()).thenReturn(CALLER_ID);

        mockMvc.perform(post("/api/payment/credits/cashout")
                .contentType("application/json")
                .content(cashOutBody())
                .with(authentication(parent())))
            .andExpect(status().isNoContent());
        verify(cashOutService).processCashOut(CALLER_ID, new BigDecimal("5.00"));
    }

    @Test
    void cashOut_subCentAmount_returns400() throws Exception {
        // fix/payment-money-integrity: euro money into a numeric(10,2) ledger — reject, never round.
        mockMvc.perform(post("/api/payment/credits/cashout")
                .contentType("application/json")
                .content("{\"amount\": 5.005}")
                .with(authentication(parent())))
            .andExpect(status().isBadRequest());
    }
}
