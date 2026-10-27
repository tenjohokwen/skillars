package com.softropic.skillars.platform.video.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.infrastructure.jwt.JwtSecretService;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.video.contract.InitializeUploadResponse;
import com.softropic.skillars.platform.video.service.QuotaConfigService;
import com.softropic.skillars.platform.video.service.VideoDeletionService;
import com.softropic.skillars.platform.video.service.VideoMetrics;
import com.softropic.skillars.platform.video.service.VideoService;
import com.softropic.skillars.platform.video.repo.VideoQuotaRepository;
import com.softropic.skillars.platform.video.repo.VideoRepository;
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
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VideoResource.class)
@Import(PlayerUploadResourceWebMvcTest.TestSecurityConfig.class)
class PlayerUploadResourceWebMvcTest {

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

    private static final String PLAYER_UPLOAD_URL = "/api/video/player/uploads/initiate";
    private static final long PLAYER_PROFILE_ID = 123_000_001L;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

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

    private static final InitializeUploadResponse STUB_RESPONSE = new InitializeUploadResponse(
        UUID.randomUUID(), UUID.randomUUID(), "bunny-player-guid",
        "https://video.bunnycdn.com/tusupload",
        Instant.now().plusSeconds(3600), "abc123hex", 9_999_999_999L, 12345L);

    @Test
    @WithMockUser(roles = "USER")
    void initiatePlayerUpload_homeworkType_returns201() throws Exception {
        stubPlayerPrincipal(PLAYER_PROFILE_ID);
        when(videoService.initializeUpload(any())).thenReturn(STUB_RESPONSE);

        String body = objectMapper.writeValueAsString(Map.of(
            "fileName", "homework.mp4",
            "fileSizeBytes", 10_485_760,
            "mimeType", "video/mp4",
            "videoType", "HOMEWORK"
        ));

        mockMvc.perform(post(PLAYER_UPLOAD_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.videoId").isNotEmpty())
            .andExpect(jsonPath("$.providerUploadId").value("bunny-player-guid"));
    }

    @Test
    @WithMockUser(roles = "PLAYER")
    void initiatePlayerUpload_selfRegisteredPlayerRole_ownerIsOwnProfileId() throws Exception {
        // Self-registered players carry ROLE_PLAYER only (PlayerRegistrationService) — used to 403.
        stubPlayerPrincipal(PLAYER_PROFILE_ID);
        when(videoService.initializeUpload(any())).thenReturn(STUB_RESPONSE);

        mockMvc.perform(post(PLAYER_UPLOAD_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(homeworkBody()))
            .andExpect(status().isCreated());

        // ownerId must be the PlayerProfile id (what the minor gate / quota tier / lifecycle read),
        // never the caller's user id.
        verify(videoService).initializeUpload(argThat(r -> String.valueOf(PLAYER_PROFILE_ID).equals(r.ownerId())));
    }

    @Test
    @WithMockUser(roles = "PLAYER")
    void initiatePlayerUpload_noOwnPlayerProfile_returns403() throws Exception {
        when(securityUtil.requireCurrentUserId()).thenReturn(PLAYER_USER_ID);
        when(playerProfileRepository.findByUserId(PLAYER_USER_ID)).thenReturn(Optional.empty());

        mockMvc.perform(post(PLAYER_UPLOAD_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(homeworkBody()))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    void initiatePlayerUpload_drillDemoType_returns422() throws Exception {
        stubPlayerPrincipal(PLAYER_PROFILE_ID);

        String body = objectMapper.writeValueAsString(Map.of(
            "fileName", "drill.mp4",
            "fileSizeBytes", 10_485_760,
            "mimeType", "video/mp4",
            "videoType", "DRILL_DEMO"
        ));

        mockMvc.perform(post(PLAYER_UPLOAD_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @WithMockUser(roles = "USER")
    void initiatePlayerUpload_coachReviewType_returns422() throws Exception {
        stubPlayerPrincipal(PLAYER_PROFILE_ID);

        String body = objectMapper.writeValueAsString(Map.of(
            "fileName", "review.mp4",
            "fileSizeBytes", 10_485_760,
            "mimeType", "video/mp4",
            "videoType", "COACH_REVIEW"
        ));

        mockMvc.perform(post(PLAYER_UPLOAD_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @WithMockUser(roles = "COACH")
    void initiatePlayerUpload_coachRole_returns403() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "fileName", "homework.mp4",
            "fileSizeBytes", 10_485_760,
            "mimeType", "video/mp4",
            "videoType", "HOMEWORK"
        ));

        mockMvc.perform(post(PLAYER_UPLOAD_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void initiatePlayerUpload_unauthenticated_returns401() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "fileName", "homework.mp4",
            "fileSizeBytes", 10_485_760,
            "mimeType", "video/mp4",
            "videoType", "HOMEWORK"
        ));

        mockMvc.perform(post(PLAYER_UPLOAD_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnauthorized());
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    // businessId is the caller's USER id, distinct from the PlayerProfile id on purpose.
    private static final long PLAYER_USER_ID = 77_000_002L;

    private void stubPlayerPrincipal(Long playerProfileId) {
        Principal principal = mock(Principal.class);
        when(principal.getBusinessId()).thenReturn(String.valueOf(PLAYER_USER_ID));
        when(securityUtil.getCurrentUser()).thenReturn(principal);
        when(securityUtil.requireCurrentUserId()).thenReturn(PLAYER_USER_ID);
        PlayerProfile profile = new PlayerProfile();
        profile.setId(playerProfileId);
        when(playerProfileRepository.findByUserId(PLAYER_USER_ID)).thenReturn(Optional.of(profile));
    }

    private String homeworkBody() throws Exception {
        return objectMapper.writeValueAsString(Map.of(
            "fileName", "homework.mp4",
            "fileSizeBytes", 10_485_760,
            "mimeType", "video/mp4",
            "videoType", "HOMEWORK"
        ));
    }
}
