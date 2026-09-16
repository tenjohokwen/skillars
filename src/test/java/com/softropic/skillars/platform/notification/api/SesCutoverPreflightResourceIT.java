package com.softropic.skillars.platform.notification.api;

import com.softropic.skillars.infrastructure.ses.SesAccountChecker;
import com.softropic.skillars.platform.notification.contract.EmailDeliveryStatus;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntity;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntityRepository;
import com.softropic.skillars.platform.notification.service.MailManager;
import com.softropic.skillars.platform.security.infrastructure.jwt.JwtSecretService;
import com.softropic.skillars.platform.video.service.VideoMetrics;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * skillars-deferred-114 AC5: resource-slice end-to-end test for
 * {@link SesCutoverPreflightResource} — the request/response shape and {@code @PreAuthorize}
 * enforcement. {@code app.email.transport=ses} is forced via {@code @TestPropertySource} because
 * this resource (and the {@link SesAccountChecker} bean it depends on) is transport-gated and the
 * shared integration context runs {@code transport=log} (see {@code application.yaml}) — a
 * {@code @WebMvcTest} slice builds its own independent context regardless
 * ({@code IntegrationTestConventionTest} exempts all Spring Boot test slices), so this needs no
 * {@code AbstractIntegrationTest} fork.
 *
 * <p>{@code MailManager}/{@code EnvelopeEntityRepository} are mocked here to exercise this
 * resource's own SENT/FAILED mapping logic in isolation; the real send path (real circuit breaker,
 * real retry template, a real container-backed {@code EnvelopeEntityRepository}) is proven
 * separately by every other {@code MailManager}-based IT in this module (e.g.
 * {@code MailManagerRateLimitIT}, {@code VideoModerationAdminAlertEnvelopeIT}) — this class's job is
 * the resource layer: request validation, response shape, and authorization.
 *
 * <p><strong>First {@code @PreAuthorize}-enforcement test in this codebase</strong> (per this
 * story's own audit: {@code AlertRuleAdminResource} has no dedicated test file, and
 * {@code AppEndpointsConventionTest} only anchors the {@code permitAll()} pattern, not
 * {@code @PreAuthorize} enforcement) — written from scratch, following {@code AdminFinanceResourceIT}'s
 * {@code @WebMvcTest} + hand-rolled method-security {@code TestSecurityConfig} pattern.
 */
@WebMvcTest(SesCutoverPreflightResource.class)
@Import(SesCutoverPreflightResourceIT.TestSecurityConfig.class)
// context-fork: this resource only exists under app.email.transport=ses; the shared integration
// context runs transport=log, so this slice needs its own property to get the bean registered.
@TestPropertySource(properties = "app.email.transport=ses")
class SesCutoverPreflightResourceIT {

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

    @Autowired private MockMvc mockMvc;

    @MockitoBean private SesAccountChecker sesAccountChecker;
    @MockitoBean private MailManager mailManager;
    @MockitoBean private EnvelopeEntityRepository envelopeEntityRepository;
    // Required by VideoApiAdvice/SecurityAdviceFilter, global beans that @WebMvcTest pulls into
    // every slice context regardless of which controller class is under test (mirrors
    // AdminFinanceResourceIT's identical pair of mocks).
    @MockitoBean private VideoMetrics videoMetrics;
    @MockitoBean private JwtSecretService jwtSecretService;

    private static final String ENDPOINT = "/v1/admin/ses/preflight";

    private void stubHealthyAccount() {
        when(sesAccountChecker.checkLive())
            .thenReturn(new SesAccountChecker.AccountStatus(true, true, "HEALTHY", null));
    }

    private void stubTestSend(EmailDeliveryStatus status, String error) {
        EnvelopeEntity entity = new EnvelopeEntity();
        entity.setStatus(status);
        entity.setError(error);
        when(envelopeEntityRepository.findBySendId(anyString())).thenReturn(entity);
    }

    private String requestBody() {
        return "{\"recipientEmail\":\"admin@skillars-test.com\"}";
    }

    // ── Role enforcement ─────────────────────────────────────────

    @Test
    void preflight_unauthenticated_returns401or403() throws Exception {
        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(requestBody()))
            .andExpect(status().is4xxClientError());
    }

    @Test
    @WithMockUser(roles = "COACH")
    void preflight_nonAdminRole_returns403() throws Exception {
        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(requestBody()))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "PARENT")
    void preflight_parentRole_returns403() throws Exception {
        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(requestBody()))
            .andExpect(status().isForbidden());
    }

    // ── Endpoint end-to-end ───────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void preflight_adminRole_successfulSend_returns200WithReadyTrue() throws Exception {
        stubHealthyAccount();
        stubTestSend(EmailDeliveryStatus.SENT, null);

        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(requestBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountCheck.sendingEnabled").value(true))
            .andExpect(jsonPath("$.accountCheck.productionAccessEnabled").value(true))
            .andExpect(jsonPath("$.accountCheck.enforcementStatus").value("HEALTHY"))
            .andExpect(jsonPath("$.accountCheck.error").doesNotExist())
            .andExpect(jsonPath("$.testSend.status").value("SENT"))
            .andExpect(jsonPath("$.testSend.error").doesNotExist())
            .andExpect(jsonPath("$.ready").value(true));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void preflight_adminRole_simulatedSendFailure_returns200WithReadyFalseAndError() throws Exception {
        stubHealthyAccount();
        stubTestSend(EmailDeliveryStatus.FAILED, "SES SendEmail permission denied");

        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(requestBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.testSend.status").value("FAILED"))
            .andExpect(jsonPath("$.testSend.error").value("SES SendEmail permission denied"))
            .andExpect(jsonPath("$.ready").value(false));
    }

    @Test
    @WithMockUser(roles = "LTD_ADMIN")
    void preflight_ltdAdminRole_isAlsoAuthorized() throws Exception {
        stubHealthyAccount();
        stubTestSend(EmailDeliveryStatus.SENT, null);

        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(requestBody()))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void preflight_blankRecipientEmail_returns400() throws Exception {
        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientEmail\":\"\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void preflight_malformedRecipientEmail_returns400() throws Exception {
        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientEmail\":\"not-an-email\"}"))
            .andExpect(status().isBadRequest());
    }
}
