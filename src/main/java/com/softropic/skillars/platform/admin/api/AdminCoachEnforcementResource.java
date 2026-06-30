package com.softropic.skillars.platform.admin.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.admin.contract.AdminManualStrikeRequest;
import com.softropic.skillars.platform.admin.contract.AdminManualStrikeResponse;
import com.softropic.skillars.platform.admin.contract.AdminReinstateCoachRequest;
import com.softropic.skillars.platform.admin.contract.AdminSuspendCoachRequest;
import com.softropic.skillars.platform.admin.contract.CoachEnforcementListItemDto;
import com.softropic.skillars.platform.admin.contract.CoachEnforcementProfileDto;
import com.softropic.skillars.platform.admin.service.AdminCoachEnforcementService;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/coaches")
@RequiredArgsConstructor
@Observed(name = "admin.coach.enforcement")
public class AdminCoachEnforcementResource {

    private final AdminCoachEnforcementService enforcementService;
    private final SecurityUtil securityUtil;

    @GetMapping("/{coachId}/enforcement")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "admin.coach.enforcement.profile")
    public ResponseEntity<CoachEnforcementProfileDto> getEnforcementProfile(@PathVariable UUID coachId) {
        return ResponseEntity.ok(enforcementService.getEnforcementProfile(coachId));
    }

    @PostMapping("/{coachId}/suspend")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "admin.coach.suspend")
    public ResponseEntity<Void> suspendCoach(
            @PathVariable UUID coachId,
            @Valid @RequestBody AdminSuspendCoachRequest request) {
        enforcementService.suspendCoach(coachId, request.reason(), request.notifyCoach(), resolveAdminId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{coachId}/reinstate")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "admin.coach.reinstate")
    public ResponseEntity<Void> reinstateCoach(
            @PathVariable UUID coachId,
            @Valid @RequestBody AdminReinstateCoachRequest request) {
        enforcementService.reinstateCoach(coachId, request.reason(), resolveAdminId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{coachId}/strikes")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "admin.coach.strike.issue")
    public ResponseEntity<AdminManualStrikeResponse> issueManualStrike(
            @PathVariable UUID coachId,
            @Valid @RequestBody AdminManualStrikeRequest request) {
        UUID strikeId = enforcementService.issueManualStrike(coachId, request.bookingId(), request.reason(), resolveAdminId());
        return ResponseEntity.status(HttpStatus.CREATED).body(new AdminManualStrikeResponse(strikeId));
    }

    @DeleteMapping("/{coachId}/strikes/{strikeId}")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "admin.coach.strike.delete")
    public ResponseEntity<Void> deleteStrike(
            @PathVariable UUID coachId,
            @PathVariable UUID strikeId,
            @NotBlank @Size(max = 500) @RequestParam String reason) {
        enforcementService.deleteStrike(coachId, strikeId, reason, resolveAdminId());
        return ResponseEntity.ok().build();
    }

    @GetMapping
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "admin.coach.list")
    public ResponseEntity<Page<CoachEnforcementListItemDto>> getCoachesUnderEnforcement(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(enforcementService.getCoachesUnderEnforcement(status, page));
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
