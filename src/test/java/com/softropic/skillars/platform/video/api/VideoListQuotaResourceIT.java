package com.softropic.skillars.platform.video.api;

import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.infrastructure.jwt.JwtSecretService;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.VideoType;
import com.softropic.skillars.platform.video.contract.Visibility;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoQuota;
import com.softropic.skillars.platform.video.repo.VideoQuotaRepository;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import com.softropic.skillars.platform.video.service.QuotaConfigService;
import com.softropic.skillars.platform.video.service.VideoDeletionService;
import com.softropic.skillars.platform.video.service.VideoMetrics;
import com.softropic.skillars.platform.video.service.VideoService;

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

import java.util.List;
import java.util.Optional;

import jakarta.servlet.http.HttpServletResponse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VideoResource.class)
@Import(VideoListQuotaResourceIT.TestSecurityConfig.class)
class VideoListQuotaResourceIT {

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

    private static final long PLAYER_PROFILE_ID = 55_000_001L;
    private static final String PLAYER_OWNER_ID = String.valueOf(PLAYER_PROFILE_ID);

    @Autowired MockMvc mockMvc;

    @MockitoBean VideoService videoService;
    @MockitoBean VideoDeletionService videoDeletionService;
    @MockitoBean CoachProfileService coachProfileService;
    @MockitoBean SecurityUtil securityUtil;
    @MockitoBean VideoMetrics videoMetrics;
    @MockitoBean JwtSecretService jwtSecretService;
    @MockitoBean PlayerProfileRepository playerProfileRepository;
    @MockitoBean VideoRepository videoRepository;
    @MockitoBean VideoQuotaRepository videoQuotaRepository;
    @MockitoBean QuotaConfigService quotaConfigService;

    // ─── GET /api/video/my ────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "USER")
    void getMyVideos_player_returns200WithList() throws Exception {
        stubPlayerPrincipal(PLAYER_PROFILE_ID);
        Video video = stubVideo("asset-my-1", OperationalState.READY);
        when(videoRepository.findByOwnerIdAndOperationalStateNotInOrderByCreatedAtDesc(
                anyString(), any()))
            .thenReturn(List.of(video));

        mockMvc.perform(get("/api/video/my"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$[0].operationalState").value("READY"))
            .andExpect(jsonPath("$[0].videoType").value("HOMEWORK"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void getMyVideos_emptyList_returns200EmptyArray() throws Exception {
        stubPlayerPrincipal(PLAYER_PROFILE_ID);
        when(videoRepository.findByOwnerIdAndOperationalStateNotInOrderByCreatedAtDesc(
                anyString(), any()))
            .thenReturn(List.of());

        mockMvc.perform(get("/api/video/my"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @WithAnonymousUser
    void getMyVideos_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/video/my"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "PARENT")
    void getMyVideos_parentRole_returns403() throws Exception {
        mockMvc.perform(get("/api/video/my"))
            .andExpect(status().isForbidden());
    }

    // ─── GET /api/video/quotas/me ─────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "USER")
    void getMyQuota_playerWithQuotaRow_returnsUsedAndLimit() throws Exception {
        stubPlayerPrincipal(PLAYER_PROFILE_ID);

        VideoQuota quota = new VideoQuota();
        quota.setUserId(PLAYER_OWNER_ID);
        quota.setStorageUsedBytes(500_000_000L);
        quota.setBandwidthUsedBytes(1_000_000_000L);
        when(videoQuotaRepository.findById(PLAYER_OWNER_ID)).thenReturn(Optional.of(quota));
        when(quotaConfigService.getStorageQuotaBytes(PLAYER_OWNER_ID)).thenReturn(5_368_709_120L);
        when(quotaConfigService.getBandwidthQuotaBytesMonthly(PLAYER_OWNER_ID)).thenReturn(10_737_418_240L);

        mockMvc.perform(get("/api/video/quotas/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.storageUsedBytes").value(500_000_000L))
            .andExpect(jsonPath("$.storageLimitBytes").value(5_368_709_120L))
            .andExpect(jsonPath("$.bandwidthUsedBytes").value(1_000_000_000L))
            .andExpect(jsonPath("$.bandwidthLimitBytes").value(10_737_418_240L));
    }

    @Test
    @WithMockUser(roles = "USER")
    void getMyQuota_noQuotaRow_returnsZerosWithTierLimits() throws Exception {
        stubPlayerPrincipal(PLAYER_PROFILE_ID);
        when(videoQuotaRepository.findById(PLAYER_OWNER_ID)).thenReturn(Optional.empty());
        when(quotaConfigService.getStorageQuotaBytes(PLAYER_OWNER_ID)).thenReturn(5_368_709_120L);
        when(quotaConfigService.getBandwidthQuotaBytesMonthly(PLAYER_OWNER_ID)).thenReturn(10_737_418_240L);

        mockMvc.perform(get("/api/video/quotas/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.storageUsedBytes").value(0))
            .andExpect(jsonPath("$.storageLimitBytes").value(5_368_709_120L))
            .andExpect(jsonPath("$.bandwidthUsedBytes").value(0));
    }

    @Test
    @WithAnonymousUser
    void getMyQuota_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/video/quotas/me"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "PARENT")
    void getMyQuota_parentRole_returns403() throws Exception {
        mockMvc.perform(get("/api/video/quotas/me"))
            .andExpect(status().isForbidden());
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private void stubPlayerPrincipal(Long playerId) {
        Principal principal = mock(Principal.class);
        when(principal.getBusinessId()).thenReturn(String.valueOf(playerId));
        when(securityUtil.getCurrentUser()).thenReturn(principal);
        when(securityUtil.requireCurrentUserId()).thenReturn(playerId);
        when(securityUtil.isCurrentUserInRole(anyString())).thenReturn(false);
    }

    private Video stubVideo(String providerAssetId, OperationalState state) {
        Video v = new Video();
        v.setOwnerId(PLAYER_OWNER_ID);
        v.setProvider("bunny");
        v.setProviderAssetId(providerAssetId);
        v.setTitle("test-video.mp4");
        v.setOperationalState(state);
        v.setAccessState(AccessState.ACTIVE);
        v.setVisibility(Visibility.PRIVATE);
        v.setVideoType(VideoType.HOMEWORK);
        return v;
    }
}
