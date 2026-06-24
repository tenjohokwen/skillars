package com.softropic.skillars.platform.video.api;

import com.softropic.skillars.platform.marketplace.service.PlayerProfileService;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.infrastructure.jwt.JwtSecretService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.video.contract.VideoApprovalResponse;
import com.softropic.skillars.platform.video.contract.exception.VideoAlreadyResolvedException;
import com.softropic.skillars.platform.video.contract.exception.VideoApprovalNotFoundException;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import com.softropic.skillars.platform.video.service.VideoApprovalService;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({VideoApprovalResource.class, VideoApiAdvice.class})
@Import(VideoApprovalResourceIT.TestSecurityConfig.class)
class VideoApprovalResourceIT {

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

    private static final UUID APPROVAL_ID = UUID.fromString("aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb");
    private static final Long PARENT_ID = 42L;

    @Autowired MockMvc mockMvc;

    @MockitoBean VideoApprovalService videoApprovalService;
    @MockitoBean SecurityUtil securityUtil;
    @MockitoBean PlayerProfileService playerProfileService;
    @MockitoBean VideoRepository videoRepository;
    @MockitoBean JwtSecretService jwtSecretService;
    @MockitoBean VideoMetrics videoMetrics;

    // ─── GET /api/video/approvals ─────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "PARENT")
    void listPendingApprovals_parent_returns200WithList() throws Exception {
        stubParentPrincipal(PARENT_ID);
        when(playerProfileService.getPlayerNameByPlayerId(any())).thenReturn("Test Player");
        VideoApprovalResponse response = new VideoApprovalResponse(
            APPROVAL_ID, UUID.randomUUID(), 100L, "Test Player", "HOMEWORK", "PENDING", Instant.now());
        when(videoApprovalService.getPendingApprovalsForParent(PARENT_ID))
            .thenReturn(List.of(stubApprovalRequest()));

        mockMvc.perform(get("/api/video/approvals"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    @WithMockUser(roles = "PARENT")
    void listPendingApprovals_emptyList_returns200EmptyArray() throws Exception {
        stubParentPrincipal(PARENT_ID);
        when(videoApprovalService.getPendingApprovalsForParent(PARENT_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/video/approvals"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @WithMockUser(roles = "COACH")
    void listPendingApprovals_coachRole_returns403() throws Exception {
        mockMvc.perform(get("/api/video/approvals"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    void listPendingApprovals_playerRole_returns403() throws Exception {
        mockMvc.perform(get("/api/video/approvals"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void listPendingApprovals_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/video/approvals"))
            .andExpect(status().isUnauthorized());
    }

    // ─── PUT /api/video/approvals/{id}/approve ────────────────────────────────

    @Test
    @WithMockUser(roles = "PARENT")
    void approveVideo_parent_returns204() throws Exception {
        stubParentPrincipal(PARENT_ID);
        doNothing().when(videoApprovalService).approveVideo(APPROVAL_ID, PARENT_ID);

        mockMvc.perform(put("/api/video/approvals/{id}/approve", APPROVAL_ID))
            .andExpect(status().isNoContent());

        verify(videoApprovalService).approveVideo(APPROVAL_ID, PARENT_ID);
    }

    @Test
    @WithMockUser(roles = "PARENT")
    void approveVideo_approvalNotFound_returns404() throws Exception {
        stubParentPrincipal(PARENT_ID);
        doThrow(new VideoApprovalNotFoundException(APPROVAL_ID))
            .when(videoApprovalService).approveVideo(eq(APPROVAL_ID), any());

        mockMvc.perform(put("/api/video/approvals/{id}/approve", APPROVAL_ID))
            .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "PARENT")
    void approveVideo_alreadyResolved_returns409() throws Exception {
        stubParentPrincipal(PARENT_ID);
        doThrow(new VideoAlreadyResolvedException(APPROVAL_ID, "APPROVED"))
            .when(videoApprovalService).approveVideo(eq(APPROVAL_ID), any());

        mockMvc.perform(put("/api/video/approvals/{id}/approve", APPROVAL_ID))
            .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "COACH")
    void approveVideo_coachRole_returns403() throws Exception {
        mockMvc.perform(put("/api/video/approvals/{id}/approve", APPROVAL_ID))
            .andExpect(status().isForbidden());
    }

    // ─── PUT /api/video/approvals/{id}/reject ─────────────────────────────────

    @Test
    @WithMockUser(roles = "PARENT")
    void rejectVideo_parent_returns204() throws Exception {
        stubParentPrincipal(PARENT_ID);
        doNothing().when(videoApprovalService).rejectVideo(APPROVAL_ID, PARENT_ID);

        mockMvc.perform(put("/api/video/approvals/{id}/reject", APPROVAL_ID))
            .andExpect(status().isNoContent());

        verify(videoApprovalService).rejectVideo(APPROVAL_ID, PARENT_ID);
    }

    @Test
    @WithMockUser(roles = "PARENT")
    void rejectVideo_alreadyResolved_returns409() throws Exception {
        stubParentPrincipal(PARENT_ID);
        doThrow(new VideoAlreadyResolvedException(APPROVAL_ID, "REJECTED"))
            .when(videoApprovalService).rejectVideo(eq(APPROVAL_ID), any());

        mockMvc.perform(put("/api/video/approvals/{id}/reject", APPROVAL_ID))
            .andExpect(status().isConflict());
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private void stubParentPrincipal(Long parentId) {
        Principal principal = org.mockito.Mockito.mock(Principal.class);
        when(principal.getBusinessId()).thenReturn(String.valueOf(parentId));
        when(securityUtil.getCurrentUser()).thenReturn(principal);
    }

    private com.softropic.skillars.platform.video.repo.VideoApprovalRequest stubApprovalRequest() {
        com.softropic.skillars.platform.video.repo.VideoApprovalRequest req =
            new com.softropic.skillars.platform.video.repo.VideoApprovalRequest();
        req.setVideoId(UUID.randomUUID());
        req.setPlayerId(100L);
        req.setParentId(PARENT_ID);
        req.setStatus("PENDING");
        return req;
    }
}
