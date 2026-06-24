package com.softropic.skillars.platform.video.api;

import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.infrastructure.jwt.JwtSecretService;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.video.repo.VideoQuotaRepository;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import com.softropic.skillars.platform.video.service.QuotaConfigService;
import com.softropic.skillars.platform.video.contract.exception.VideoDeletionNotAuthorisedException;
import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
import com.softropic.skillars.platform.video.service.VideoAccessGuard;
import com.softropic.skillars.platform.video.service.VideoDeletionService;
import com.softropic.skillars.platform.video.service.VideoMetrics;
import com.softropic.skillars.platform.video.service.VideoService;
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

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({VideoResource.class, VideoApiAdvice.class})
@Import(VideoDeleteResourceIT.TestSecurityConfig.class)
class VideoDeleteResourceIT {

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

    @MockitoBean VideoService videoService;
    @MockitoBean(name = "videoAccessGuard") VideoAccessGuard videoAccessGuard;
    @MockitoBean VideoDeletionService videoDeletionService;
    @MockitoBean SecurityUtil securityUtil;
    @MockitoBean CoachProfileService coachProfileService;
    @MockitoBean VideoMetrics videoMetrics;
    @MockitoBean JwtSecretService jwtSecretService;
    @MockitoBean PlayerProfileRepository playerProfileRepository;
    @MockitoBean VideoRepository videoRepository;
    @MockitoBean VideoQuotaRepository videoQuotaRepository;
    @MockitoBean QuotaConfigService quotaConfigService;

    private static final UUID VIDEO_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String OWNER = "player-42";

    @Test
    @WithMockUser(username = OWNER)
    void delete_owner_returns204() throws Exception {
        when(videoAccessGuard.canDelete(any(), eq(VIDEO_ID))).thenReturn(true);
        when(securityUtil.getCurrentUserName()).thenReturn(OWNER);
        doNothing().when(videoDeletionService).deleteByUser(eq(VIDEO_ID), eq(OWNER));

        mockMvc.perform(delete("/api/video/{id}", VIDEO_ID))
            .andExpect(status().isNoContent());

        verify(videoDeletionService).deleteByUser(VIDEO_ID, OWNER);
    }

    @Test
    @WithMockUser(username = "other-user")
    void delete_nonOwner_guardDenies_returns403() throws Exception {
        doThrow(new VideoDeletionNotAuthorisedException(VIDEO_ID, "other-user"))
            .when(videoAccessGuard).canDelete(any(), eq(VIDEO_ID));

        mockMvc.perform(delete("/api/video/{id}", VIDEO_ID))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorMsg.errorKey").value("video.deletionNotAuthorised"));

        verify(videoDeletionService, never()).deleteByUser(any(), any());
    }

    @Test
    @WithMockUser(username = "any-user")
    void delete_purgedVideo_returns404() throws Exception {
        doThrow(new VideoNotFoundException(VIDEO_ID))
            .when(videoAccessGuard).canDelete(any(), eq(VIDEO_ID));

        mockMvc.perform(delete("/api/video/{id}", VIDEO_ID))
            .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "coach@example.com")
    void delete_coachWithActiveBooking_returns403() throws Exception {
        doThrow(new VideoDeletionNotAuthorisedException(VIDEO_ID, "coach@example.com"))
            .when(videoAccessGuard).canDelete(any(), eq(VIDEO_ID));

        mockMvc.perform(delete("/api/video/{id}", VIDEO_ID))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorMsg.errorKey").value("video.deletionNotAuthorised"));

        verify(videoDeletionService, never()).deleteByUser(any(), any());
    }

    @Test
    @WithAnonymousUser
    void delete_unauthenticated_returns401() throws Exception {
        mockMvc.perform(delete("/api/video/{id}", VIDEO_ID))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = OWNER)
    void delete_adminUser_returns204() throws Exception {
        when(videoAccessGuard.canDelete(any(), eq(VIDEO_ID))).thenReturn(true);
        when(securityUtil.getCurrentUserName()).thenReturn("admin@example.com");
        doNothing().when(videoDeletionService).deleteByUser(eq(VIDEO_ID), eq("admin@example.com"));

        mockMvc.perform(delete("/api/video/{id}", VIDEO_ID))
            .andExpect(status().isNoContent());
    }
}
