package com.softropic.skillars.platform.security.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.service.LoginAttemptsService;

import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import static net.logstash.logback.argument.StructuredArguments.kv;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints for managing login-attempt locks.
 * All operations require ADMIN or LTD_ADMIN authority.
 */
@Observed(name = "http.admin.login-lock")
@Slf4j
@RestController
@RequestMapping("/v1/admin")
@RequiredArgsConstructor
public class AdminLoginResource {

    private final LoginAttemptsService loginAttemptsService;

    /**
     * Immediately clears all login-attempt locks for a user.
     * <p>
     * Use when a legitimate user is locked out due to too many failed attempts
     * and cannot wait for the 4-hour cache window to expire on its own.
     * </p>
     *
     * @param username the login (email / id) of the user to unlock
     * @param admin    the authenticated admin performing this action (for audit logging)
     * @return 204 No Content on success, 400 if username is blank
     */
    @DeleteMapping("/users/{username}/login-lock")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    public ResponseEntity<Void> unlockUserLoginAttempts(@PathVariable final String username,
                                                        @AuthenticationPrincipal final UserDetails admin) {
        if (StringUtils.isBlank(username)) {
            return ResponseEntity.badRequest().build();
        }
        log.info("Admin cleared login-attempt locks",
            kv("operation", "admin_action"),
            kv("action", "clear_login_attempts"),
            kv("status", "SUCCESS"));
        loginAttemptsService.unlockUser(username);
        return ResponseEntity.noContent().build();
    }
}
