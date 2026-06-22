package com.softropic.skillars.platform.video.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.infrastructure.jwt.JwtSecretService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.video.contract.InitializeUploadResponse;
import com.softropic.skillars.platform.video.contract.exception.VideoValidationException;
import com.softropic.skillars.platform.video.service.VideoMetrics;
import com.softropic.skillars.platform.video.service.VideoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import jakarta.servlet.http.HttpServletResponse;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VideoResource.class)
@Import(VideoUploadResourceIT.TestSecurityConfig.class)
class VideoUploadResourceIT {

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

    private static final String INITIATE_URL = "/api/video/uploads/initiate";
    private static final UUID COACH_ID = UUID.fromString("aaaaaaaa-0001-0002-0003-bbbbbbbbbbbb");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private VideoService videoService;

    @MockitoBean
    private CoachProfileService coachProfileService;

    @MockitoBean
    private SecurityUtil securityUtil;

    // Required by VideoApiAdvice which is picked up by the @WebMvcTest web slice
    @MockitoBean
    private VideoMetrics videoMetrics;

    // Required by SecurityAdviceFilter which is picked up by the @WebMvcTest web slice
    @MockitoBean
    private JwtSecretService jwtSecretService;

    private static final InitializeUploadResponse STUB_RESPONSE = new InitializeUploadResponse(
        UUID.fromString("11111111-1111-1111-1111-111111111111"),
        UUID.fromString("22222222-2222-2222-2222-222222222222"),
        "bunny-guid-abc",
        "https://video.bunnycdn.com/tusupload",
        Instant.now().plusSeconds(3600),
        "abc123hex",
        9_999_999_999L,
        12345L
    );

    @Test
    @WithMockUser(roles = "COACH")
    void initiateUpload_validCoachRequest_returns201WithTusFields() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(1001L);
        when(coachProfileService.getCoachIdByUserId(1001L)).thenReturn(COACH_ID);
        when(videoService.initializeUpload(any())).thenReturn(STUB_RESPONSE);

        String body = objectMapper.writeValueAsString(Map.of(
            "fileName", "clip.mp4",
            "fileSizeBytes", 10_485_760,
            "mimeType", "video/mp4",
            "videoType", "COACH_REVIEW"
        ));

        mockMvc.perform(post(INITIATE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.videoId").value(STUB_RESPONSE.videoId().toString()))
            .andExpect(jsonPath("$.sessionId").value(STUB_RESPONSE.sessionId().toString()))
            .andExpect(jsonPath("$.providerUploadId").value("bunny-guid-abc"))
            .andExpect(jsonPath("$.signedUploadUrl").value("https://video.bunnycdn.com/tusupload"))
            .andExpect(jsonPath("$.tusAuthorizationSignature").value("abc123hex"))
            .andExpect(jsonPath("$.tusAuthorizationExpire").value(9_999_999_999L))
            .andExpect(jsonPath("$.tusLibraryId").value(12345));
    }

    @Test
    @WithMockUser(roles = "COACH")
    void initiateUpload_missingVideoType_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "fileName", "clip.mp4",
            "fileSizeBytes", 10_485_760,
            "mimeType", "video/mp4"
        ));

        mockMvc.perform(post(INITIATE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "COACH")
    void initiateUpload_homeworkTypeAsCoach_returns422() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(1001L);
        when(coachProfileService.getCoachIdByUserId(1001L)).thenReturn(COACH_ID);

        String body = objectMapper.writeValueAsString(Map.of(
            "fileName", "clip.mp4",
            "fileSizeBytes", 10_485_760,
            "mimeType", "video/mp4",
            "videoType", "HOMEWORK"
        ));

        mockMvc.perform(post(INITIATE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @WithAnonymousUser
    void initiateUpload_unauthenticated_returns401() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "fileName", "clip.mp4",
            "fileSizeBytes", 10_485_760,
            "mimeType", "video/mp4",
            "videoType", "COACH_REVIEW"
        ));

        mockMvc.perform(post(INITIATE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    void initiateUpload_playerRole_returns403() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "fileName", "clip.mp4",
            "fileSizeBytes", 10_485_760,
            "mimeType", "video/mp4",
            "videoType", "COACH_REVIEW"
        ));

        mockMvc.perform(post(INITIATE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "COACH")
    void initiateUpload_fileSizeBytesZero_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "fileName", "clip.mp4",
            "fileSizeBytes", 0,
            "mimeType", "video/mp4",
            "videoType", "COACH_REVIEW"
        ));

        mockMvc.perform(post(INITIATE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "COACH")
    void initiateUpload_invalidVideoTypeString_returns400() throws Exception {
        String body = "{\"fileName\":\"clip.mp4\",\"fileSizeBytes\":10485760,\"mimeType\":\"video/mp4\",\"videoType\":\"NOT_A_TYPE\"}";

        mockMvc.perform(post(INITIATE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "COACH")
    void initiateUpload_invalidMimeType_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "fileName", "clip.mp4",
            "fileSizeBytes", 10_485_760,
            "mimeType", "application/pdf",
            "videoType", "COACH_REVIEW"
        ));

        mockMvc.perform(post(INITIATE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "COACH")
    void initiateUpload_fileSizeExceedsTypeLimit_returns422() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(1001L);
        when(coachProfileService.getCoachIdByUserId(1001L)).thenReturn(COACH_ID);
        when(videoService.initializeUpload(any()))
            .thenThrow(new VideoValidationException("File size exceeds COACH_REVIEW limit"));

        String body = objectMapper.writeValueAsString(Map.of(
            "fileName", "big.mp4",
            "fileSizeBytes", 2_147_483_648L,
            "mimeType", "video/mp4",
            "videoType", "COACH_REVIEW"
        ));

        mockMvc.perform(post(INITIATE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnprocessableEntity());
    }
}
