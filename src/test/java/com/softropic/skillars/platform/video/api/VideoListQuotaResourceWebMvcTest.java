package com.softropic.skillars.platform.video.api;

import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.infrastructure.jwt.JwtSecretService;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.service.PlayerOwnershipGuard;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.InitializeUploadResponse;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.RetryUploadRequest;
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

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VideoResource.class)
@Import({VideoListQuotaResourceWebMvcTest.TestSecurityConfig.class, PlayerOwnershipGuard.class})
class VideoListQuotaResourceWebMvcTest {

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
    @MockitoBean com.softropic.skillars.platform.video.service.MinorUploadPolicy minorUploadPolicy;
    @MockitoBean com.softropic.skillars.platform.video.service.VideoSubjectService videoSubjectService;

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
    @WithMockUser(roles = "PLAYER")
    void getMyVideos_selfRegisteredPlayerRole_returns200ScopedToOwnProfile() throws Exception {
        // Self-registered players carry ROLE_PLAYER only — this used to be a 403 (ROLE_USER/COACH only).
        stubPlayerPrincipal(PLAYER_PROFILE_ID);
        Video video = stubVideo("asset-my-2", OperationalState.READY);
        when(videoRepository.findByOwnerIdAndOperationalStateNotInOrderByCreatedAtDesc(
                eq(PLAYER_OWNER_ID), any()))
            .thenReturn(List.of(video));

        mockMvc.perform(get("/api/video/my"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].ownerId").value(PLAYER_OWNER_ID));
    }

    @Test
    @WithMockUser(roles = "PLAYER")
    void getMyVideos_playerWithoutOwnProfile_returns403() throws Exception {
        when(securityUtil.requireCurrentUserId()).thenReturn(PLAYER_USER_ID);
        when(securityUtil.isCurrentUserInRole(anyString())).thenReturn(false);
        when(playerProfileRepository.findByUserId(PLAYER_USER_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/video/my"))
            .andExpect(status().isForbidden());
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

    // ─── FAILED videos are listed (so the owner can retry them) ───────────────

    @Test
    @WithMockUser(roles = "USER")
    @SuppressWarnings("unchecked")
    void getMyVideos_includesFailed_excludesOnlyDeletedAndPurged() throws Exception {
        stubPlayerPrincipal(PLAYER_PROFILE_ID);
        when(videoRepository.findByOwnerIdAndOperationalStateNotInOrderByCreatedAtDesc(anyString(), any()))
            .thenReturn(List.of(stubVideo("asset-failed", OperationalState.FAILED)));

        mockMvc.perform(get("/api/video/my"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].operationalState").value("FAILED"));

        org.mockito.ArgumentCaptor<Collection<OperationalState>> excluded =
            org.mockito.ArgumentCaptor.forClass(Collection.class);
        verify(videoRepository).findByOwnerIdAndOperationalStateNotInOrderByCreatedAtDesc(anyString(), excluded.capture());
        org.assertj.core.api.Assertions.assertThat(excluded.getValue())
            .containsExactlyInAnyOrder(OperationalState.DELETED, OperationalState.PURGED);
    }

    // ─── GET /api/video/players/{playerId}/videos (parent view of a linked child) ─

    private static final long PARENT_USER_ID = 66_000_001L;

    @Test
    void getPlayerVideos_parentOfPlayer_returns200WithChildVideos() throws Exception {
        when(playerProfileRepository.existsByIdAndParentId(PLAYER_PROFILE_ID, PARENT_USER_ID)).thenReturn(true);
        when(videoRepository.findByOwnerIdAndOperationalStateNotInOrderByCreatedAtDesc(eq(PLAYER_OWNER_ID), any()))
            .thenReturn(List.of(stubVideo("asset-child", OperationalState.READY)));

        mockMvc.perform(get("/api/video/players/" + PLAYER_PROFILE_ID + "/videos")
                .with(authentication(skillarsAuth(PARENT_USER_ID, "ROLE_PARENT"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].ownerId").value(PLAYER_OWNER_ID))
            .andExpect(jsonPath("$[0].operationalState").value("READY"));
    }

    @Test
    void getPlayerVideos_unrelatedParent_returns403() throws Exception {
        when(playerProfileRepository.existsByIdAndParentId(PLAYER_PROFILE_ID, PARENT_USER_ID)).thenReturn(false);
        when(playerProfileRepository.existsByIdAndUserId(PLAYER_PROFILE_ID, PARENT_USER_ID)).thenReturn(false);

        mockMvc.perform(get("/api/video/players/" + PLAYER_PROFILE_ID + "/videos")
                .with(authentication(skillarsAuth(PARENT_USER_ID, "ROLE_PARENT"))))
            .andExpect(status().isForbidden());
        verify(videoRepository, never()).findByOwnerIdAndOperationalStateNotInOrderByCreatedAtDesc(anyString(), any());
    }

    // ─── V184: coach videos ABOUT a player (subject link) ─────────────────────

    @Test
    void getCoachVideosAboutPlayer_parentOfPlayer_returnsSubjectVideosWithoutFailedOrHidden() throws Exception {
        when(playerProfileRepository.existsByIdAndParentId(PLAYER_PROFILE_ID, PARENT_USER_ID)).thenReturn(true);
        Video review = stubVideo("asset-review", OperationalState.READY);
        review.setOwnerId(UUID.randomUUID().toString());
        review.setSubjectBookingId(UUID.randomUUID());
        review.setSubjectPlayerId(PLAYER_PROFILE_ID);
        when(videoRepository.findBySubjectPlayerIdAndOperationalStateNotInOrderByCreatedAtDesc(eq(PLAYER_PROFILE_ID), any()))
            .thenReturn(List.of(review));

        mockMvc.perform(get("/api/video/players/" + PLAYER_PROFILE_ID + "/coach-videos")
                .with(authentication(skillarsAuth(PARENT_USER_ID, "ROLE_PARENT"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].subjectPlayerId").value(PLAYER_PROFILE_ID))
            .andExpect(jsonPath("$[0].operationalState").value("READY"));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Collection<OperationalState>> excluded =
            org.mockito.ArgumentCaptor.forClass(Collection.class);
        verify(videoRepository).findBySubjectPlayerIdAndOperationalStateNotInOrderByCreatedAtDesc(eq(PLAYER_PROFILE_ID), excluded.capture());
        org.assertj.core.api.Assertions.assertThat(excluded.getValue()).contains(OperationalState.FAILED, OperationalState.HIDDEN,
            OperationalState.LOCKED, OperationalState.PURGED);
    }

    @Test
    void getCoachVideosAboutPlayer_unrelatedParent_returns403() throws Exception {
        when(playerProfileRepository.existsByIdAndParentId(PLAYER_PROFILE_ID, PARENT_USER_ID)).thenReturn(false);
        when(playerProfileRepository.existsByIdAndUserId(PLAYER_PROFILE_ID, PARENT_USER_ID)).thenReturn(false);

        mockMvc.perform(get("/api/video/players/" + PLAYER_PROFILE_ID + "/coach-videos")
                .with(authentication(skillarsAuth(PARENT_USER_ID, "ROLE_PARENT"))))
            .andExpect(status().isForbidden());
        verify(videoRepository, never()).findBySubjectPlayerIdAndOperationalStateNotInOrderByCreatedAtDesc(any(), any());
    }

    @Test
    @WithMockUser(roles = "PLAYER")
    void getMyCoachVideos_selfRegisteredPlayer_scopedToOwnProfile() throws Exception {
        stubPlayerPrincipal(PLAYER_PROFILE_ID);
        when(videoRepository.findBySubjectPlayerIdAndOperationalStateNotInOrderByCreatedAtDesc(eq(PLAYER_PROFILE_ID), any()))
            .thenReturn(List.of());

        mockMvc.perform(get("/api/video/my/coach-videos"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());
        verify(videoRepository).findBySubjectPlayerIdAndOperationalStateNotInOrderByCreatedAtDesc(eq(PLAYER_PROFILE_ID), any());
    }

    // ─── POST /api/video/{id}/retry ──────────────────────────────────────────

    @Test
    @WithMockUser(roles = "COACH")
    void retryUpload_coachOwner_delegatesWithResolvedOwnerAndReturnsCredentials() throws Exception {
        UUID videoId = UUID.randomUUID();
        UUID coachId = UUID.randomUUID();
        when(securityUtil.isCurrentUserInRole("ROLE_COACH")).thenReturn(true);
        when(securityUtil.getCurrentCoachUserId()).thenReturn(77L);
        when(coachProfileService.getCoachIdByUserId(77L)).thenReturn(coachId);
        when(videoService.retryUpload(new RetryUploadRequest(videoId, coachId.toString(), 2048L)))
            .thenReturn(new InitializeUploadResponse(videoId, UUID.randomUUID(), "bunny-guid",
                "https://tus", Instant.now().plusSeconds(600), "sig", 9_999_999_999L, 1L));

        mockMvc.perform(post("/api/video/" + videoId + "/retry")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"fileSizeBytes\":2048}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.videoId").value(videoId.toString()))
            .andExpect(jsonPath("$.providerUploadId").value("bunny-guid"));
    }

    @Test
    @WithMockUser(roles = "COACH")
    void retryUpload_nonPositiveSize_returns400() throws Exception {
        mockMvc.perform(post("/api/video/" + UUID.randomUUID() + "/retry")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"fileSizeBytes\":0}"))
            .andExpect(status().isBadRequest());
        verify(videoService, never()).retryUpload(any());
    }

    @Test
    @WithMockUser(roles = "PARENT")
    void retryUpload_parentRole_returns403() throws Exception {
        mockMvc.perform(post("/api/video/" + UUID.randomUUID() + "/retry")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"fileSizeBytes\":2048}"))
            .andExpect(status().isForbidden());
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    /** An authentication whose principal is a Skillars {@link Principal} — what PlayerOwnershipGuard reads. */
    private static org.springframework.security.core.Authentication skillarsAuth(long userId, String role) {
        Principal principal = mock(Principal.class);
        when(principal.getBusinessId()).thenReturn(String.valueOf(userId));
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            principal, null,
            List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(role)));
    }

    // businessId is the caller's USER id — deliberately different from the PlayerProfile id so a
    // regression back to "userId as ownerId" queries the wrong owner and fails the assertions.
    private static final long PLAYER_USER_ID = 77_000_001L;

    private void stubPlayerPrincipal(Long playerProfileId) {
        Principal principal = mock(Principal.class);
        when(principal.getBusinessId()).thenReturn(String.valueOf(PLAYER_USER_ID));
        when(securityUtil.getCurrentUser()).thenReturn(principal);
        when(securityUtil.requireCurrentUserId()).thenReturn(PLAYER_USER_ID);
        when(securityUtil.isCurrentUserInRole(anyString())).thenReturn(false);
        PlayerProfile profile = new PlayerProfile();
        profile.setId(playerProfileId);
        when(playerProfileRepository.findByUserId(PLAYER_USER_ID)).thenReturn(Optional.of(profile));
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
