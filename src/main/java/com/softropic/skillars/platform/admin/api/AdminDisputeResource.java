package com.softropic.skillars.platform.admin.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.admin.contract.AdminDisputeDetailDto;
import com.softropic.skillars.platform.admin.contract.AdminDismissDisputeRequest;
import com.softropic.skillars.platform.admin.contract.AdminResolveDisputeRequest;
import com.softropic.skillars.platform.admin.service.DisputeService;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/disputes")
@RequiredArgsConstructor
@Observed(name = "admin.disputes")
public class AdminDisputeResource {

    private final DisputeService disputeService;
    private final SecurityUtil securityUtil;

    @GetMapping("/{disputeId}")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "admin.disputes.detail")
    public ResponseEntity<AdminDisputeDetailDto> getDisputeDetail(@PathVariable UUID disputeId) {
        return ResponseEntity.ok(disputeService.getAdminDisputeDetail(disputeId));
    }

    @PostMapping("/{disputeId}/resolve")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "admin.disputes.resolve")
    public ResponseEntity<Void> resolveDispute(
            @PathVariable UUID disputeId,
            @Valid @RequestBody AdminResolveDisputeRequest request) {
        disputeService.resolveDispute(
            disputeId, request.resolution(), request.creditAmount(), request.resolutionNote(), resolveAdminId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{disputeId}/dismiss")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "admin.disputes.dismiss")
    public ResponseEntity<Void> dismissDispute(
            @PathVariable UUID disputeId,
            @Valid @RequestBody AdminDismissDisputeRequest request) {
        disputeService.dismissDispute(disputeId, request.reason(), resolveAdminId());
        return ResponseEntity.ok().build();
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
