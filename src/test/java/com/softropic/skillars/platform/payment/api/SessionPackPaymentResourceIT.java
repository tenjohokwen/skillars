package com.softropic.skillars.platform.payment.api;

import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.config.PaymentProperties;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.contract.SavedPaymentMethodResponse;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.payment.service.PackSessionService;
import com.softropic.skillars.platform.payment.service.SessionPackPaymentService;
import com.softropic.skillars.platform.security.infrastructure.jwt.JwtSecretService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
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
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deferred-11 AC 1 / AC 4: publishable-key config endpoint and saved-payment-method endpoint.
 */
@WebMvcTest({SessionPackPaymentResource.class, PaymentApiAdvice.class})
@Import(SessionPackPaymentResourceIT.TestSecurityConfig.class)
class SessionPackPaymentResourceIT {

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
    @WithMockUser(roles = "COACH")
    void getSavedPaymentMethod_coachRole_returns403() throws Exception {
        mockMvc.perform(get("/api/payment/payment-method"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void getSavedPaymentMethod_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/payment/payment-method"))
            .andExpect(status().isUnauthorized());
    }
}
