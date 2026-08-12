package com.softropic.skillars.platform.booking.api;

import com.softropic.skillars.platform.booking.service.BookingCompletionService;
import com.softropic.skillars.platform.booking.service.BookingDuplicationService;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.platform.booking.service.RescheduleService;
import com.softropic.skillars.platform.security.infrastructure.jwt.JwtSecretService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.session.repo.SessionRepository;
import com.softropic.skillars.platform.session.service.DrillSuggestionService;
import com.softropic.skillars.platform.video.service.VideoMetrics;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UAT.5 review finding: no regression test proved a PLAYER caller stays rejected on the three
 * endpoints this story deliberately left parent-only, even though two of them ({@code /reschedule}
 * via "Request Change" and {@code /confirm-completion}) sit on the exact page ({@code
 * ParentBookingsPage.vue}) this story's AC4 exposed to players. {@code /cancel} had no test
 * coverage at all before this story (a pre-existing gap, confirmed by an empty grep for
 * {@code CancellationResource} across {@code src/test}) — closed here alongside the other two so
 * a future widening of any of the three cannot silently regress without a failing test.
 *
 * <p>Asserts only the {@code @PreAuthorize} boundary — the same shape {@code
 * SessionPackPaymentResourceIT} uses for its own {@code coachRole_returns403} case — not the
 * service-layer behaviour behind it, which is out of scope for this story.
 */
@WebMvcTest({CancellationResource.class, RescheduleResource.class, SessionCompletionResource.class})
class PlayerRoleLockoutIT {

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

    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final UUID RESCHEDULE_ID = UUID.randomUUID();

    @Autowired MockMvc mockMvc;

    @MockitoBean BookingService bookingService;
    @MockitoBean RescheduleService rescheduleService;
    @MockitoBean BookingDuplicationService duplicationService;
    @MockitoBean BookingCompletionService bookingCompletionService;
    @MockitoBean SessionRepository sessionRepository;
    @MockitoBean DrillSuggestionService drillSuggestionService;
    @MockitoBean SecurityUtil securityUtil;
    @MockitoBean JwtSecretService jwtSecretService;
    @MockitoBean VideoMetrics videoMetrics;

    @Test
    @WithMockUser(roles = "PLAYER")
    void cancelBooking_playerRole_returns403() throws Exception {
        mockMvc.perform(post("/api/bookings/" + BOOKING_ID + "/cancel"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "PLAYER")
    void requestReschedule_playerRole_returns403() throws Exception {
        mockMvc.perform(post("/api/bookings/" + BOOKING_ID + "/reschedule")
                .contentType("application/json")
                .content("{\"proposedStartTime\":\"2027-01-01T10:00:00Z\",\"proposedEndTime\":\"2027-01-01T11:00:00Z\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "PLAYER")
    void confirmCompletion_playerRole_returns403() throws Exception {
        mockMvc.perform(put("/api/bookings/" + BOOKING_ID + "/confirm-completion"))
            .andExpect(status().isForbidden());
    }
}
