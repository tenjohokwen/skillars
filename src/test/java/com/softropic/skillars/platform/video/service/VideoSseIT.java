package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.security.infrastructure.jwt.JwtSecretService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.video.api.VideoEventResource;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
import com.softropic.skillars.platform.video.repo.Video;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VideoEventResource.class)
@Import(VideoSseIT.TestSecurityConfig.class)
class VideoSseIT {

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
    @Autowired ApplicationEventPublisher applicationEventPublisher;

    @MockitoBean VideoService videoService;
    @MockitoBean VideoSseService videoSseService;
    @MockitoBean SecurityUtil securityUtil;
    @MockitoBean VideoMetrics videoMetrics;
    @MockitoBean JwtSecretService jwtSecretService;

    private static final UUID VIDEO_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String OWNER = "owner@example.com";

    @Test
    @WithAnonymousUser
    void subscribeToEvents_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/video/{id}/events", VIDEO_ID))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = OWNER)
    void subscribeToEvents_nonOwner_returns403() throws Exception {
        Video video = videoWithOwner("other-owner@example.com");
        when(videoService.findById(VIDEO_ID)).thenReturn(video);
        when(securityUtil.getCurrentUserName()).thenReturn(OWNER);

        mockMvc.perform(get("/api/video/{id}/events", VIDEO_ID))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = OWNER)
    void subscribeToEvents_owner_startsAsyncSseStream() throws Exception {
        Video video = videoWithOwner(OWNER);
        when(videoService.findById(VIDEO_ID)).thenReturn(video);
        when(securityUtil.getCurrentUserName()).thenReturn(OWNER);
        when(videoSseService.subscribe(VIDEO_ID, OperationalState.SCANNING.name()))
            .thenReturn(new SseEmitter(5000L));

        // asyncStarted() confirms the endpoint: authenticated, ownership verified, SseEmitter returned
        mockMvc.perform(get("/api/video/{id}/events", VIDEO_ID)
                .accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(request().asyncStarted());
    }

    // AC11: SSE endpoint wires VideoSseService.subscribe() with the video's current state so
    // VideoSseService can send the initial status and subsequent VideoStatusChangedEvent pushes.
    @Test
    @WithMockUser(username = OWNER)
    void subscribeToEvents_passesCurrentStateToSseService() throws Exception {
        Video video = videoWithOwner(OWNER);
        video.setOperationalState(OperationalState.TRANSCODING);
        when(videoService.findById(VIDEO_ID)).thenReturn(video);
        when(securityUtil.getCurrentUserName()).thenReturn(OWNER);
        when(videoSseService.subscribe(VIDEO_ID, OperationalState.TRANSCODING.name()))
            .thenReturn(new SseEmitter(5000L));

        mockMvc.perform(get("/api/video/{id}/events", VIDEO_ID)
                .accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(request().asyncStarted());

        ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);
        verify(videoSseService).subscribe(org.mockito.ArgumentMatchers.eq(VIDEO_ID), stateCaptor.capture());
        assertThat(stateCaptor.getValue()).isEqualTo(OperationalState.TRANSCODING.name());
    }

    // AC12: GET /api/video/{id}/status returns VideoStatusResponse record with videoId and operationalState.
    @Test
    @WithMockUser(username = OWNER)
    void getStatus_owner_returnsVideoStatusResponseRecord() throws Exception {
        Video video = videoWithOwner(OWNER);
        when(videoService.findById(VIDEO_ID)).thenReturn(video);
        when(securityUtil.getCurrentUserName()).thenReturn(OWNER);

        mockMvc.perform(get("/api/video/{id}/status", VIDEO_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.videoId").value(VIDEO_ID.toString()))
            .andExpect(jsonPath("$.operationalState").value("SCANNING"));
    }

    // AC12: Status endpoint returns 404 when video does not exist.
    @Test
    @WithMockUser(username = OWNER)
    void getStatus_videoNotFound_returns404() throws Exception {
        when(videoService.findById(VIDEO_ID)).thenThrow(new VideoNotFoundException(VIDEO_ID));
        when(securityUtil.getCurrentUserName()).thenReturn(OWNER);

        mockMvc.perform(get("/api/video/{id}/status", VIDEO_ID))
            .andExpect(status().isNotFound());
    }

    private Video videoWithOwner(String ownerId) {
        Video v = new Video();
        v.setOwnerId(ownerId);
        v.setOperationalState(OperationalState.SCANNING);
        return v;
    }
}
