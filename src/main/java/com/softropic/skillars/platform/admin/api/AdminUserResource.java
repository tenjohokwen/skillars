package com.softropic.skillars.platform.admin.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.admin.contract.AdminLoginUnlockResponse;
import com.softropic.skillars.platform.admin.contract.AdminUserDetailDto;
import com.softropic.skillars.platform.admin.contract.AdminUserStatusRequest;
import com.softropic.skillars.platform.admin.contract.AdminUserSummaryDto;
import com.softropic.skillars.platform.admin.service.AdminUserService;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin console account enforcement (D7 / A10): user search, account suspend / reactivate for any
 * non-admin user, and failed-login rate-limit unlock. See {@link AdminUserService} for semantics.
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@Observed(name = "admin.users")
public class AdminUserResource {

    private final AdminUserService adminUserService;
    private final SecurityUtil securityUtil;

    @GetMapping
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "admin.users.search")
    public ResponseEntity<Page<AdminUserSummaryDto>> searchUsers(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(adminUserService.searchUsers(q, page));
    }

    @GetMapping("/{userId}")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "admin.users.detail")
    public ResponseEntity<AdminUserDetailDto> getUser(@PathVariable Long userId) {
        return ResponseEntity.ok(adminUserService.getUserDetail(userId));
    }

    @PostMapping("/{userId}/suspend")
    @PreAuthorize(SecurityConstants.HAS_FULL_ADMIN_ROLE)
    @Observed(name = "admin.users.suspend")
    public ResponseEntity<Void> suspendUser(
            @PathVariable Long userId,
            @Valid @RequestBody AdminUserStatusRequest request) {
        adminUserService.suspendUser(userId, request.reason(), resolveAdminId(),
            request.cancelUpcomingBookingsOrDefault());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/reactivate")
    @PreAuthorize(SecurityConstants.HAS_FULL_ADMIN_ROLE)
    @Observed(name = "admin.users.reactivate")
    public ResponseEntity<Void> reactivateUser(
            @PathVariable Long userId,
            @Valid @RequestBody AdminUserStatusRequest request) {
        adminUserService.reactivateUser(userId, request.reason(), resolveAdminId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/unlock-login")
    @PreAuthorize(SecurityConstants.HAS_FULL_ADMIN_ROLE)
    @Observed(name = "admin.users.unlockLogin")
    public ResponseEntity<AdminLoginUnlockResponse> unlockLogin(@PathVariable Long userId) {
        return ResponseEntity.ok(new AdminLoginUnlockResponse(adminUserService.unlockLogin(userId, resolveAdminId())));
    }

    private Long resolveAdminId() {
        Object principal = securityUtil.getCurrentUser();
        if (!(principal instanceof Principal p)) {
            throw new InsufficientAuthenticationException("Unexpected principal type");
        }
        try {
            return Long.parseLong(p.getBusinessId());
        } catch (NumberFormatException e) {
            throw new InsufficientAuthenticationException("Invalid business ID");
        }
    }
}
