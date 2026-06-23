package com.softropic.skillars.platform.video.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.security.infrastructure.jwt.JwtSecretService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.video.contract.PlaybackAuthorizationResponse;
import com.softropic.skillars.platform.video.contract.exception.PlaybackDeniedException;
import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.service.PlaybackService;
import com.softropic.skillars.platform.video.service.VideoAccessCache;
import com.softropic.skillars.platform.video.service.VideoAccessGuard;
import com.softropic.skillars.platform.video.service.VideoMetrics;
import com.softropic.skillars.platform.video.service.VideoService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({VideoPlayResource.class, VideoApiAdvice.class})
@Import(VideoPlayResourceIT.TestSecurityConfig.class)
class VideoPlayResourceIT {

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
    @Autowired ObjectMapper objectMapper;

    @MockitoBean PlaybackService playbackService;
    @MockitoBean VideoService videoService;
    @MockitoBean SecurityUtil securityUtil;
    @MockitoBean VideoMetrics videoMetrics;
    @MockitoBean JwtSecretService jwtSecretService;
    @MockitoBean(name = "videoAccessGuard") VideoAccessGuard videoAccessGuard;
    @MockitoBean VideoAccessCache videoAccessCache;

    private static final UUID VIDEO_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String OWNER = "player-42";

    @BeforeEach
    void stubCache() {
        when(videoAccessCache.getVideo(any())).thenReturn(Optional.empty());
    }

    private Video ownerVideo() {
        Video v = new Video();
        v.setOwnerId("42");
        return v;
    }

    private void stubGuardAllow() {
        when(videoAccessGuard.canPlay(any(), eq(VIDEO_ID))).thenReturn(true);
        when(videoAccessGuard.isParentOf(any(), any(), any())).thenReturn(false);
    }

    @Test
    @WithMockUser(username = OWNER)
    void play_readyActiveVideo_returns200WithSignedUrl() throws Exception {
        stubGuardAllow();
        when(securityUtil.getCurrentUserName()).thenReturn(OWNER);
        when(videoService.findById(VIDEO_ID)).thenReturn(ownerVideo());
        when(playbackService.authorizePlayback(eq(VIDEO_ID), eq(OWNER), nullable(String.class), eq(false)))
            .thenReturn(new PlaybackAuthorizationResponse(
                "jwt-token", "https://cdn.example.com/asset/playlist.m3u8?token=abc", Instant.now().plusSeconds(7200)));

        mockMvc.perform(get("/api/video/{id}/play", VIDEO_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.signedHlsUrl").value(containsString("playlist.m3u8")))
            .andExpect(jsonPath("$.expiresAt").exists())
            .andExpect(jsonPath("$.downloadUrl").doesNotExist());
    }

    @Test
    @WithMockUser(username = OWNER)
    void play_lockedVideo_returns403WithNotAccessibleCode() throws Exception {
        stubGuardAllow();
        when(securityUtil.getCurrentUserName()).thenReturn(OWNER);
        when(videoService.findById(VIDEO_ID)).thenReturn(ownerVideo());
        when(playbackService.authorizePlayback(eq(VIDEO_ID), eq(OWNER), nullable(String.class), anyBoolean()))
            .thenThrow(new PlaybackDeniedException(VIDEO_ID, OWNER));

        mockMvc.perform(get("/api/video/{id}/play", VIDEO_ID))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorMsg.errorKey").value("video.notAccessible"));
    }

    @Test
    @WithMockUser(username = "non-owner")
    void play_guardDenies_returns403() throws Exception {
        // Guard throws PlaybackDeniedException when access is denied
        doThrow(new PlaybackDeniedException(VIDEO_ID, "non-owner"))
            .when(videoAccessGuard).canPlay(any(), eq(VIDEO_ID));

        mockMvc.perform(get("/api/video/{id}/play", VIDEO_ID))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "any-user")
    void play_purgedVideo_returns404() throws Exception {
        doThrow(new VideoNotFoundException(VIDEO_ID))
            .when(videoAccessGuard).canPlay(any(), eq(VIDEO_ID));

        mockMvc.perform(get("/api/video/{id}/play", VIDEO_ID))
            .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "parent-99")
    void play_parent_skipHiddenCheckTrue_callsAuthorizeWithSkip() throws Exception {
        when(videoAccessGuard.canPlay(any(), eq(VIDEO_ID))).thenReturn(true);
        when(securityUtil.getCurrentUserName()).thenReturn("parent-99");
        when(videoService.findById(VIDEO_ID)).thenReturn(ownerVideo());
        when(videoAccessGuard.isParentOf(eq("parent-99"), eq("42"), eq(VIDEO_ID))).thenReturn(true);
        when(playbackService.authorizePlayback(eq(VIDEO_ID), eq("parent-99"), nullable(String.class), eq(true)))
            .thenReturn(new PlaybackAuthorizationResponse(
                "jwt", "https://cdn.example.com/asset/playlist.m3u8?token=abc", Instant.now().plusSeconds(7200)));

        mockMvc.perform(get("/api/video/{id}/play", VIDEO_ID))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = OWNER)
    void play_videoWithDownloadUrl_includesDownloadUrl() throws Exception {
        stubGuardAllow();
        when(securityUtil.getCurrentUserName()).thenReturn(OWNER);
        when(videoService.findById(VIDEO_ID)).thenReturn(ownerVideo());
        when(playbackService.authorizePlayback(eq(VIDEO_ID), eq(OWNER), nullable(String.class), eq(false)))
            .thenReturn(new PlaybackAuthorizationResponse(
                "jwt", "https://cdn.example.com/asset/playlist.m3u8?token=x",
                Instant.now().plusSeconds(7200), "https://cdn.example.com/asset/original?token=y"));

        mockMvc.perform(get("/api/video/{id}/play", VIDEO_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.downloadUrl").value(containsString("/original")));
    }

    @Test
    @WithMockUser(username = OWNER)
    void play_ipBindingEnabled_lastXffEntryIsUsedAsClientIp() throws Exception {
        stubGuardAllow();
        when(securityUtil.getCurrentUserName()).thenReturn(OWNER);
        when(videoService.findById(VIDEO_ID)).thenReturn(ownerVideo());
        when(playbackService.authorizePlayback(eq(VIDEO_ID), eq(OWNER), eq("5.6.7.8"), eq(false)))
            .thenReturn(new PlaybackAuthorizationResponse(
                "jwt", "https://cdn.example.com/playlist.m3u8?token=x&clientIp=5.6.7.8",
                Instant.now().plusSeconds(7200)));

        mockMvc.perform(get("/api/video/{id}/play", VIDEO_ID)
                .header("X-Forwarded-For", "1.2.3.4, 5.6.7.8"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.signedHlsUrl").value(containsString("clientIp=5.6.7.8")));
    }

    @Test
    @WithMockUser(username = "coach@example.com")
    void play_coachWithActiveBooking_hiddenVideo_playbackServiceBlocks_returns403() throws Exception {
        when(videoAccessGuard.canPlay(any(), eq(VIDEO_ID))).thenReturn(true);
        when(videoAccessGuard.isParentOf(any(), any(), eq(VIDEO_ID))).thenReturn(false);
        when(securityUtil.getCurrentUserName()).thenReturn("coach@example.com");
        when(videoService.findById(VIDEO_ID)).thenReturn(ownerVideo());
        when(playbackService.authorizePlayback(eq(VIDEO_ID), eq("coach@example.com"), nullable(String.class), eq(false)))
            .thenThrow(new PlaybackDeniedException(VIDEO_ID, "coach@example.com"));

        mockMvc.perform(get("/api/video/{id}/play", VIDEO_ID))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorMsg.errorKey").value("video.notAccessible"));
    }

    @Test
    @WithAnonymousUser
    void play_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/video/{id}/play", VIDEO_ID))
            .andExpect(status().isUnauthorized());
    }
}
