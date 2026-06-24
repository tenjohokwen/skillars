package com.softropic.skillars.platform.payment.api;

import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.config.PaymentProperties;
import com.softropic.skillars.platform.payment.contract.CoachStripeStatusResponse;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.service.StripeOnboardingService;
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

import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({StripeOnboardingResource.class, PaymentApiAdvice.class})
@Import(StripeOnboardingResourceIT.TestSecurityConfig.class)
class StripeOnboardingResourceIT {

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

    private static final UUID COACH_UUID = UUID.fromString("cccccccc-1111-2222-3333-aaaaaaaaaaaa");
    private static final Long COACH_USER_ID = 42L;

    @Autowired MockMvc mockMvc;

    @MockitoBean StripeOnboardingService onboardingService;
    @MockitoBean CoachProfileRepository coachProfileRepository;
    @MockitoBean PaymentProperties paymentProperties;
    @MockitoBean SecurityUtil securityUtil;
    @MockitoBean JwtSecretService jwtSecretService;
    @MockitoBean VideoMetrics videoMetrics;

    // ─── GET /api/payment/coaches/me/stripe/onboard ───────────────────────────

    @Test
    @WithMockUser(roles = "COACH")
    void getOnboardingUrl_authenticatedCoach_returns200WithUrl() throws Exception {
        stubCoachResolution();
        when(onboardingService.generateOnboardingUrl(COACH_UUID))
            .thenReturn("https://connect.stripe.com/oauth/authorize?client_id=ca_test");

        mockMvc.perform(get("/api/payment/coaches/me/stripe/onboard"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.onboardingUrl").value("https://connect.stripe.com/oauth/authorize?client_id=ca_test"));
    }

    @Test
    @WithMockUser(roles = "PARENT")
    void getOnboardingUrl_parentRole_returns403() throws Exception {
        mockMvc.perform(get("/api/payment/coaches/me/stripe/onboard"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void getOnboardingUrl_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/payment/coaches/me/stripe/onboard"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "COACH")
    void getOnboardingUrl_stripeUnavailable_returns422() throws Exception {
        stubCoachResolution();
        when(onboardingService.generateOnboardingUrl(COACH_UUID))
            .thenThrow(new PaymentGatewayException("payment.providerUnavailable"));

        mockMvc.perform(get("/api/payment/coaches/me/stripe/onboard"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.helpCode").value("payment.providerUnavailable"));
    }

    // ─── GET /api/payment/coaches/me/stripe/status ────────────────────────────

    @Test
    @WithMockUser(roles = "COACH")
    void getStripeStatus_noAccountExists_returnsNotConnected() throws Exception {
        stubCoachResolution();
        when(onboardingService.getStripeStatus(COACH_UUID))
            .thenReturn(new CoachStripeStatusResponse(COACH_UUID, "NOT_CONNECTED", false, false));

        mockMvc.perform(get("/api/payment/coaches/me/stripe/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.onboardingStatus").value("NOT_CONNECTED"))
            .andExpect(jsonPath("$.chargesEnabled").value(false))
            .andExpect(jsonPath("$.payoutsEnabled").value(false));
    }

    @Test
    @WithMockUser(roles = "COACH")
    void getStripeStatus_completeAccount_returnsComplete() throws Exception {
        stubCoachResolution();
        when(onboardingService.getStripeStatus(COACH_UUID))
            .thenReturn(new CoachStripeStatusResponse(COACH_UUID, "COMPLETE", true, true));

        mockMvc.perform(get("/api/payment/coaches/me/stripe/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.onboardingStatus").value("COMPLETE"))
            .andExpect(jsonPath("$.chargesEnabled").value(true))
            .andExpect(jsonPath("$.payoutsEnabled").value(true));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private void stubCoachResolution() {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(COACH_USER_ID);
        CoachProfile coachProfile = new CoachProfile();
        coachProfile.setId(COACH_UUID);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coachProfile));
    }
}
