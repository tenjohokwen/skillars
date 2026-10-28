package com.softropic.skillars.platform.notification.api;

import com.softropic.skillars.platform.notification.contract.NotificationCategory;
import com.softropic.skillars.platform.notification.service.InAppNotificationService;
import com.softropic.skillars.platform.notification.service.NotificationPreferenceService;
import com.softropic.skillars.platform.security.config.AppEndpoints;
import com.softropic.skillars.platform.security.infrastructure.jwt.JwtSecretService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.video.service.VideoMetrics;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Notification centre (UI-gap chunk C9): the public one-click unsubscribe endpoint is the ONLY
 * unauthenticated route under {@code /api/notifications}. The slice's filter chain is built from the
 * production {@link AppEndpoints#PUBLIC_ENDPOINTS} list (permitAll) with everything else
 * authenticated — the same two-tier shape {@code SecurityConfiguration} applies — so adding a broader
 * pattern to that list, or forgetting the exact path, fails here.
 */
@WebMvcTest({UnsubscribeResource.class, NotificationResource.class})
@Import(UnsubscribeResourceWebMvcTest.TestSecurityConfig.class)
class UnsubscribeResourceWebMvcTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
            return http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                    .requestMatchers(AppEndpoints.PUBLIC_ENDPOINTS.toArray(new String[0])).permitAll()
                    .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(
                    (req, res, ex) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
                .build();
        }
    }

    @Autowired private MockMvc mockMvc;

    @MockitoBean private NotificationPreferenceService preferenceService;
    @MockitoBean private InAppNotificationService inAppNotificationService;
    @MockitoBean private SecurityUtil securityUtil;
    @MockitoBean private VideoMetrics videoMetrics;
    @MockitoBean private JwtSecretService jwtSecretService;

    @Test
    void unsubscribe_isReachableWithoutAuthentication_viaOneClickFormPost() throws Exception {
        when(preferenceService.unsubscribe("tok")).thenReturn(NotificationCategory.REMINDER);

        mockMvc.perform(post(UnsubscribeResource.PATH).param("token", "tok")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("List-Unsubscribe=One-Click"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.category").value("REMINDER"));
    }

    @Test
    void unsubscribe_invalidToken_is400() throws Exception {
        when(preferenceService.unsubscribe(anyString()))
            .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid unsubscribe token"));

        mockMvc.perform(post(UnsubscribeResource.PATH).param("token", "bad"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void unsubscribe_missingToken_is400_andNeverReachesTheService() throws Exception {
        mockMvc.perform(post(UnsubscribeResource.PATH))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(preferenceService);
    }

    @Test
    void unsubscribe_getIsNotAMutatingRoute() throws Exception {
        // Link scanners and prefetchers issue GETs; only POST may unsubscribe.
        int statusCode = mockMvc.perform(get(UnsubscribeResource.PATH).param("token", "tok"))
            .andReturn().getResponse().getStatus();
        assertThat(statusCode).isIn(404, 405);
        verifyNoInteractions(preferenceService);
    }

    @Test
    void everyOtherNotificationEndpoint_staysAuthenticated() throws Exception {
        mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/notifications/unread-count")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/notifications/read-all")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/notifications/00000000-0000-0000-0000-000000000001/read"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/notifications/preferences")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/notifications/preferences/REMINDER")
                .contentType(MediaType.APPLICATION_JSON).content("{\"emailEnabled\":false}"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(inAppNotificationService);
        org.mockito.Mockito.verify(preferenceService, org.mockito.Mockito.never())
            .setPreference(anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void thePublicPatternIsExact_notAPrefix() {
        assertThat(AppEndpoints.PUBLIC_ENDPOINTS)
            .contains(UnsubscribeResource.PATH)
            .noneMatch(p -> p.startsWith("/api/notifications") && !p.equals(UnsubscribeResource.PATH));
    }
}
