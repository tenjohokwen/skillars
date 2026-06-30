package com.softropic.skillars.platform.admin.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.admin.contract.DisputeCreatedResponse;
import com.softropic.skillars.platform.admin.contract.DisputeResponse;
import com.softropic.skillars.platform.admin.contract.RaiseDisputeRequest;
import com.softropic.skillars.platform.admin.service.DisputeService;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/disputes")
@RequiredArgsConstructor
@Observed(name = "disputes")
public class DisputeResource {

    private final DisputeService disputeService;
    private final SecurityUtil securityUtil;

    @PostMapping
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    @Observed(name = "disputes.raise")
    public ResponseEntity<DisputeCreatedResponse> raiseDispute(
            @Valid @RequestBody RaiseDisputeRequest request) {
        Long userId = resolveCurrentUserId();
        String role = resolveCurrentRole();
        UUID disputeId = disputeService.raiseDispute(
            request.bookingId(), request.reason(), request.details(), userId, role);
        return ResponseEntity.status(HttpStatus.CREATED).body(new DisputeCreatedResponse(disputeId));
    }

    @GetMapping("/{disputeId}")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    @Observed(name = "disputes.get")
    public ResponseEntity<DisputeResponse> getDispute(@PathVariable UUID disputeId) {
        Long userId = resolveCurrentUserId();
        return ResponseEntity.ok(disputeService.getDispute(disputeId, userId));
    }

    private Long resolveCurrentUserId() {
        Object principal = securityUtil.getCurrentUser();
        if (!(principal instanceof Principal p)) {
            throw new InsufficientAuthenticationException("Unexpected principal type");
        }
        String businessId = p.getBusinessId();
        if (businessId == null || businessId.isBlank()) {
            throw new InsufficientAuthenticationException("Principal has no business ID");
        }
        try {
            return Long.parseLong(businessId);
        } catch (NumberFormatException e) {
            throw new InsufficientAuthenticationException("Invalid business ID");
        }
    }

    // Returns "PARENT" or "PLAYER" based on granted authorities; fallback is "PLAYER".
    private String resolveCurrentRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_PARENT"))) {
            return "PARENT";
        }
        return "PLAYER";
    }
}
