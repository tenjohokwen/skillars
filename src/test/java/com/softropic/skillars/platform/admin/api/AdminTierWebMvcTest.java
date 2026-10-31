package com.softropic.skillars.platform.admin.api;

import com.softropic.skillars.platform.admin.service.AdminReviewService;
import com.softropic.skillars.platform.admin.service.AdminUserService;
import com.softropic.skillars.platform.admin.service.GdprRequestService;
import com.softropic.skillars.platform.config.api.ConfigResource;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.notification.api.AlertRuleAdminResource;
import com.softropic.skillars.platform.notification.repo.AlertRuleRepository;
import com.softropic.skillars.platform.notification.service.AlertRuleCache;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S-28: the limited admin tier over real HTTP + method security. {@code ROLE_LTD_ADMIN} must get 200 on the
 * shared read/moderate tier and 403 on representative full-admin endpoints (user suspend, config write,
 * alert-rule write, GDPR list); {@code ROLE_ADMIN} must get 200 on both. The exhaustive per-endpoint matrix
 * lives in {@code AdminRoleTierAuditTest}; this proves the expressions are actually enforced end to end.
 *
 * <p>One slice for several controllers, so it costs a single extra test context.
 */
@WebMvcTest({AdminUserResource.class, AdminReviewResource.class, AdminGdprResource.class,
    ConfigResource.class, AlertRuleAdminResource.class})
@Import(AdminTierWebMvcTest.TestSecurityConfig.class)
class AdminTierWebMvcTest {

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

    @MockitoBean AdminUserService adminUserService;
    @MockitoBean AdminReviewService adminReviewService;
    @MockitoBean GdprRequestService gdprRequestService;
    @MockitoBean ConfigService configService;
    @MockitoBean AlertRuleRepository alertRuleRepository;
    @MockitoBean AlertRuleCache alertRuleCache;
    @MockitoBean SecurityUtil securityUtil;
    @MockitoBean JwtSecretService jwtSecretService;
    @MockitoBean VideoMetrics videoMetrics;

    private static final String REASON_BODY = "{\"reason\":\"because\"}";

    // ── shared tier: LTD_ADMIN and ADMIN both get 200 ────────────

    @Test
    @WithMockUser(roles = "LTD_ADMIN")
    void ltdAdmin_canSearchUsers() throws Exception {
        when(adminUserService.searchUsers(any(), anyInt())).thenReturn(Page.empty());
        mockMvc.perform(get("/api/admin/users")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "LTD_ADMIN")
    void ltdAdmin_canReadReviewQueue() throws Exception {
        when(adminReviewService.getUnderReviewQueue(anyInt())).thenReturn(new PageImpl<>(List.of()));
        mockMvc.perform(get("/api/admin/reviews/queue")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_canSearchUsersAndReadReviewQueue() throws Exception {
        when(adminUserService.searchUsers(any(), anyInt())).thenReturn(Page.empty());
        when(adminReviewService.getUnderReviewQueue(anyInt())).thenReturn(new PageImpl<>(List.of()));
        mockMvc.perform(get("/api/admin/users")).andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/reviews/queue")).andExpect(status().isOk());
    }

    // ── full tier: LTD_ADMIN gets 403 and the service is never reached ──

    @Test
    @WithMockUser(roles = "LTD_ADMIN")
    void ltdAdmin_cannotSuspendReactivateOrUnlockUsers() throws Exception {
        mockMvc.perform(post("/api/admin/users/1/suspend").contentType(MediaType.APPLICATION_JSON).content(REASON_BODY))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/users/1/reactivate").contentType(MediaType.APPLICATION_JSON).content(REASON_BODY))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/users/1/unlock-login")).andExpect(status().isForbidden());
        verifyNoInteractions(adminUserService);
    }

    @Test
    @WithMockUser(roles = "LTD_ADMIN")
    void ltdAdmin_cannotReadOrWriteConfig() throws Exception {
        mockMvc.perform(get("/api/config/values")).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/config/values/some.key").contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"1\"}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(configService);
    }

    @Test
    @WithMockUser(roles = "LTD_ADMIN")
    void ltdAdmin_cannotAdministerAlertRules() throws Exception {
        String body = "{\"metricName\":\"m\",\"threshold\":0.5,\"windowSeconds\":60,\"enabled\":true}";
        mockMvc.perform(post("/v1/admin/alerts").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
        mockMvc.perform(put("/v1/admin/alerts/1").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/admin/alerts")).andExpect(status().isForbidden());
        verifyNoInteractions(alertRuleRepository);
    }

    @Test
    @WithMockUser(roles = "LTD_ADMIN")
    void ltdAdmin_cannotListGdprRequests() throws Exception {
        mockMvc.perform(get("/api/admin/gdpr/requests")).andExpect(status().isForbidden());
        verifyNoInteractions(gdprRequestService);
    }

    // ── full tier: ADMIN gets 200 ────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_canReachFullTierReads() throws Exception {
        when(configService.listForAdmin()).thenReturn(List.of());
        when(alertRuleCache.getCachedRules()).thenReturn(List.of());
        when(gdprRequestService.listRequests(any(), any(), any())).thenReturn(Page.empty());
        mockMvc.perform(get("/api/config/values")).andExpect(status().isOk());
        mockMvc.perform(get("/v1/admin/alerts")).andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/gdpr/requests")).andExpect(status().isOk());
    }
}
