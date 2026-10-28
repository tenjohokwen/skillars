package com.softropic.skillars.platform.video.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.infrastructure.jwt.JwtSecretService;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.video.repo.VideoQuotaRepository;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import com.softropic.skillars.platform.video.service.QuotaConfigService;
import com.softropic.skillars.platform.video.contract.InitializeUploadResponse;
import com.softropic.skillars.platform.video.contract.exception.QuotaExceededException;
import com.softropic.skillars.platform.video.contract.exception.RateLimitExceededException;
import com.softropic.skillars.platform.video.contract.exception.VideoValidationException;
import com.softropic.skillars.platform.video.service.VideoDeletionService;
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
@Import(VideoUploadResourceWebMvcTest.TestSecurityConfig.class)
class VideoUploadResourceWebMvcTest {

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
    private VideoDeletionService videoDeletionService;

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

    // Required by VideoResource constructor (added Story 6.6)
    @MockitoBean
    private PlayerProfileRepository playerProfileRepository;
    @MockitoBean
    private VideoRepository videoRepository;
    @MockitoBean
    private VideoQuotaRepository videoQuotaRepository;
    @MockitoBean
    private QuotaConfigService quotaConfigService;

    @MockitoBean
    private com.softropic.skillars.platform.video.service.MinorUploadPolicy minorUploadPolicy;

    @MockitoBean
    private com.softropic.skillars.platform.video.service.VideoSubjectService videoSubjectService;

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

    @org.junit.jupiter.api.BeforeEach
    void noSubjectByDefault() {
        // Mockito answers 0L (not null) for a boxed Long — make "no subject" explicit.
        when(videoSubjectService.resolveSubjectPlayer(any(), any(), org.mockito.ArgumentMatchers.isNull()))
            .thenReturn(null);
    }

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

    @Test
    @WithMockUser(roles = "COACH")
    void initiateUpload_rateLimited_returns429WithRateLimitedKey() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(1001L);
        when(coachProfileService.getCoachIdByUserId(1001L)).thenReturn(COACH_ID);
        when(videoService.initializeUpload(any()))
            .thenThrow(new RateLimitExceededException(COACH_ID.toString(), "rate limit exceeded"));

        String body = objectMapper.writeValueAsString(Map.of(
            "fileName", "clip.mp4",
            "fileSizeBytes", 10_485_760,
            "mimeType", "video/mp4",
            "videoType", "COACH_REVIEW"
        ));

        mockMvc.perform(post(INITIATE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.errorMsg.errorKey").value("UPLOAD_RATE_LIMITED"));
    }

    // Deferred-33 AC6: a hard storage-quota rejection does not clear on retry, unlike the transient
    // rate-limit case above — 403, not 429.
    @Test
    @WithMockUser(roles = "COACH")
    void initiateUpload_quotaExceeded_returns403WithQuotaExceededKey() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(1001L);
        when(coachProfileService.getCoachIdByUserId(1001L)).thenReturn(COACH_ID);
        when(videoService.initializeUpload(any()))
            .thenThrow(new QuotaExceededException(COACH_ID.toString(), 900_000_000L, 10_485_760L));

        String body = objectMapper.writeValueAsString(Map.of(
            "fileName", "clip.mp4",
            "fileSizeBytes", 10_485_760,
            "mimeType", "video/mp4",
            "videoType", "COACH_REVIEW"
        ));

        mockMvc.perform(post(INITIATE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorMsg.errorKey").value("QUOTA_EXCEEDED"));
    }

    // ── V184: the subject (booking + child) of a coach video ──

    @Test
    @WithMockUser(roles = "COACH")
    void initiateUpload_coachReviewWithSubjectBooking_passesTheValidatedBookingAndItsPlayer() throws Exception {
        UUID bookingId = UUID.randomUUID();
        when(securityUtil.getCurrentCoachUserId()).thenReturn(1001L);
        when(coachProfileService.getCoachIdByUserId(1001L)).thenReturn(COACH_ID);
        when(videoSubjectService.resolveSubjectPlayer(COACH_ID,
            com.softropic.skillars.platform.video.contract.VideoType.COACH_REVIEW, bookingId)).thenReturn(77L);
        when(videoService.initializeUpload(any())).thenReturn(STUB_RESPONSE);

        String body = objectMapper.writeValueAsString(Map.of(
            "fileName", "clip.mp4",
            "fileSizeBytes", 10_485_760,
            "mimeType", "video/mp4",
            "videoType", "COACH_REVIEW",
            "subjectBookingId", bookingId.toString()
        ));

        mockMvc.perform(post(INITIATE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated());

        org.mockito.Mockito.verify(videoService).initializeUpload(org.mockito.ArgumentMatchers.argThat(r ->
            bookingId.equals(r.subjectBookingId()) && Long.valueOf(77L).equals(r.subjectPlayerId())));
    }

    @Test
    @WithMockUser(roles = "COACH")
    void initiateUpload_invalidSubject_returns422WithSubjectInvalidKey_andNothingIsUploaded() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(1001L);
        when(coachProfileService.getCoachIdByUserId(1001L)).thenReturn(COACH_ID);
        when(videoSubjectService.resolveSubjectPlayer(any(), any(), any()))
            .thenThrow(new com.softropic.skillars.platform.video.contract.exception.VideoSubjectInvalidException(
                "booking not found for this coach"));

        String body = objectMapper.writeValueAsString(Map.of(
            "fileName", "clip.mp4",
            "fileSizeBytes", 10_485_760,
            "mimeType", "video/mp4",
            "videoType", "COACH_REVIEW",
            "subjectBookingId", UUID.randomUUID().toString()
        ));

        mockMvc.perform(post(INITIATE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorMsg.errorKey").value("VIDEO_SUBJECT_INVALID"));
        org.mockito.Mockito.verify(videoService, org.mockito.Mockito.never()).initializeUpload(any());
    }

    @Test
    @WithMockUser(roles = "COACH")
    void listSubjectBookings_returnsTheCoachsHeldSessions() throws Exception {
        UUID bookingId = UUID.randomUUID();
        when(securityUtil.getCurrentCoachUserId()).thenReturn(1001L);
        when(coachProfileService.getCoachIdByUserId(1001L)).thenReturn(COACH_ID);
        when(videoSubjectService.listSubjectBookings(COACH_ID)).thenReturn(java.util.List.of(
            new com.softropic.skillars.platform.booking.contract.PlannableBookingSummary(
                bookingId, 77L, "Mia", Instant.now(), Instant.now().plusSeconds(3600), "Europe/Berlin")));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/video/coach/subject-bookings"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].bookingId").value(bookingId.toString()))
            .andExpect(jsonPath("$[0].playerName").value("Mia"));
    }

    @Test
    @WithMockUser(roles = "PLAYER")
    void listSubjectBookings_nonCoach_returns403() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/video/coach/subject-bookings"))
            .andExpect(status().isForbidden());
    }
}
