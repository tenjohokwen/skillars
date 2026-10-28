package com.softropic.skillars.platform.video.api;

import com.softropic.skillars.platform.security.infrastructure.jwt.JwtSecretService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.AdminVideoQueueFilter;
import com.softropic.skillars.platform.video.contract.AdminVideoQueueItemDto;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.Visibility;
import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
import com.softropic.skillars.platform.video.service.AdminVideoService;
import com.softropic.skillars.platform.video.service.VideoMetrics;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin video review queue (story 6.3 / FR-ADM-002): request binding, defaults and admin-only access
 * of the new list / single-video endpoints on {@link AdminVideoResource}.
 */
@WebMvcTest({AdminVideoResource.class, VideoApiAdvice.class})
@Import(AdminVideoResourceWebMvcTest.TestSecurityConfig.class)
class AdminVideoResourceWebMvcTest {

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

    @MockitoBean AdminVideoService adminVideoService;
    @MockitoBean SecurityUtil securityUtil;
    @MockitoBean JwtSecretService jwtSecretService;
    @MockitoBean VideoMetrics videoMetrics;

    private static final UUID VIDEO_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private static AdminVideoQueueItemDto item() {
        return new AdminVideoQueueItemDto(VIDEO_ID, "clip.mp4", OperationalState.LOCKED, AccessState.ACTIVE,
            "owner-1", Visibility.PRIVATE, "HOMEWORK", Instant.parse("2026-10-01T10:00:00Z"),
            Instant.parse("2026-10-02T10:00:00Z"), null, null, List.of("VIDEOINTEL"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void reviewQueue_defaultsToAttentionAndFirstPage() throws Exception {
        when(adminVideoService.getReviewQueue(AdminVideoQueueFilter.ATTENTION, 0))
            .thenReturn(new PageImpl<>(List.of(item()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/video/admin/videos"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].id").value(VIDEO_ID.toString()))
            .andExpect(jsonPath("$.content[0].operationalState").value("LOCKED"))
            .andExpect(jsonPath("$.content[0].flaggedLayers[0]").value("VIDEOINTEL"))
            .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void reviewQueue_passesFilterAndPage() throws Exception {
        when(adminVideoService.getReviewQueue(AdminVideoQueueFilter.BLOCKED, 2))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 20), 0));

        mockMvc.perform(get("/api/video/admin/videos").param("filter", "BLOCKED").param("page", "2"))
            .andExpect(status().isOk());

        verify(adminVideoService).getReviewQueue(AdminVideoQueueFilter.BLOCKED, 2);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void reviewQueue_unknownFilter_returns400() throws Exception {
        mockMvc.perform(get("/api/video/admin/videos").param("filter", "NOPE"))
            .andExpect(status().isBadRequest());

        verify(adminVideoService, never()).getReviewQueue(any(), anyInt());
    }

    @Test
    @WithMockUser(roles = "PLAYER")
    void reviewQueue_nonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/video/admin/videos"))
            .andExpect(status().isForbidden());

        verify(adminVideoService, never()).getReviewQueue(any(), anyInt());
    }

    @Test
    @WithAnonymousUser
    void reviewQueue_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/video/admin/videos"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getVideo_returnsItem() throws Exception {
        when(adminVideoService.getVideo(VIDEO_ID)).thenReturn(item());

        mockMvc.perform(get("/api/video/admin/videos/{id}", VIDEO_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("clip.mp4"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getVideo_unknown_returns404() throws Exception {
        when(adminVideoService.getVideo(VIDEO_ID)).thenThrow(new VideoNotFoundException(VIDEO_ID));

        mockMvc.perform(get("/api/video/admin/videos/{id}", VIDEO_ID))
            .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "LTD_ADMIN")
    void getVideo_limitedAdmin_isAllowed() throws Exception {
        when(adminVideoService.getVideo(VIDEO_ID)).thenReturn(item());

        mockMvc.perform(get("/api/video/admin/videos/{id}", VIDEO_ID))
            .andExpect(status().isOk());
    }
}
