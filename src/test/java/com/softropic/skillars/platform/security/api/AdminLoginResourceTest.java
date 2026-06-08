package com.softropic.skillars.platform.security.api;

import com.softropic.skillars.platform.security.infrastructure.jwt.JwtSecretService;
import com.softropic.skillars.platform.security.service.LoginAttemptsService;
import com.softropic.skillars.platform.video.service.VideoMetrics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminLoginResource.class)
@Import(AdminLoginResourceTest.TestSecurityConfig.class)
class AdminLoginResourceTest {

    /**
     * Minimal security config for the web slice:
     * - enables @PreAuthorize processing
     * - requires authentication on all requests (so the auth enforcement is actually exercised)
     * - disables CSRF so DELETE requests don't need a token
     */
    @TestConfiguration
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .build();
        }
    }

    private static final String LOCK_URL       = "/v1/admin/users/{username}/login-lock";
    private static final String TARGET_USERNAME = "locked.user@example.com";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LoginAttemptsService loginAttemptsService;

    // Required by SecurityAdviceFilter which is a @Component picked up by the web slice scan
    @MockitoBean
    private JwtSecretService jwtSecretService;

    // Required by VideoApiAdvice which is a @RestControllerAdvice picked up by the web slice scan
    @MockitoBean
    private VideoMetrics videoMetrics;

    // ── Authorization ─────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void givenAdmin_whenDeleteLoginLock_thenReturns204() throws Exception {
        mockMvc.perform(delete(LOCK_URL, TARGET_USERNAME))
               .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(username = "ltdAdmin@example.com", roles = "LTD_ADMIN")
    void givenLtdAdmin_whenDeleteLoginLock_thenReturns204() throws Exception {
        mockMvc.perform(delete(LOCK_URL, TARGET_USERNAME))
               .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "USER")
    void givenRegularUser_whenDeleteLoginLock_thenReturns403() throws Exception {
        mockMvc.perform(delete(LOCK_URL, TARGET_USERNAME))
               .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void givenUnauthenticated_whenDeleteLoginLock_thenDenied() throws Exception {
        mockMvc.perform(delete(LOCK_URL, TARGET_USERNAME))
               .andExpect(status().is4xxClientError());
    }

    // ── Service delegation ────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void givenAdmin_whenDeleteLoginLock_thenDelegatesToServiceWithCorrectUsername() throws Exception {
        mockMvc.perform(delete(LOCK_URL, TARGET_USERNAME))
               .andExpect(status().isNoContent());

        verify(loginAttemptsService).unlockUser(TARGET_USERNAME);
    }

    @Test
    @WithMockUser(roles = "LTD_ADMIN")
    void givenLtdAdmin_whenDeleteLoginLock_thenDelegatesToService() throws Exception {
        mockMvc.perform(delete(LOCK_URL, TARGET_USERNAME))
               .andExpect(status().isNoContent());

        verify(loginAttemptsService).unlockUser(TARGET_USERNAME);
    }

    /**
     * The controller passes the username path variable to the service as-is, including any
     * special characters. Key escaping is the service's responsibility, not the controller's.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    void givenUsernameContainsPipeChar_whenDeleteLoginLock_thenPassedToServiceUnmodified() throws Exception {
        String usernameWithPipe = "tricky|user@example.com";

        mockMvc.perform(delete(LOCK_URL, usernameWithPipe))
               .andExpect(status().isNoContent());

        verify(loginAttemptsService).unlockUser(usernameWithPipe);
    }

    // ── Service NOT called when access is denied ──────────────────────────────

    @Test
    @WithMockUser(roles = "USER")
    void givenRegularUser_whenDeleteLoginLock_thenServiceNotCalled() throws Exception {
        mockMvc.perform(delete(LOCK_URL, TARGET_USERNAME))
               .andExpect(status().isForbidden());

        verifyNoInteractions(loginAttemptsService);
    }

    @Test
    @WithAnonymousUser
    void givenUnauthenticated_whenDeleteLoginLock_thenServiceNotCalled() throws Exception {
        mockMvc.perform(delete(LOCK_URL, TARGET_USERNAME))
               .andExpect(status().is4xxClientError());

        verifyNoInteractions(loginAttemptsService);
    }

}
