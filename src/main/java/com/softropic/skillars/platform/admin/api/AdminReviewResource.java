package com.softropic.skillars.platform.admin.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.admin.service.AdminReviewService;
import com.softropic.skillars.platform.reviews.contract.AdminReviewQueueEntryDto;
import com.softropic.skillars.platform.reviews.contract.BlockReviewRequest;
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

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/reviews")
@RequiredArgsConstructor
@Observed(name = "admin.reviews")
public class AdminReviewResource {

    private final AdminReviewService adminReviewService;
    private final SecurityUtil securityUtil;

    @GetMapping("/queue")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "admin.reviews.queue")
    public ResponseEntity<Page<AdminReviewQueueEntryDto>> getQueue(
            @RequestParam(defaultValue = "UNDER_REVIEW") String status,
            @RequestParam(defaultValue = "0") int page) {
        if (!"UNDER_REVIEW".equals(status)) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(adminReviewService.getUnderReviewQueue(Math.max(0, page)));
    }

    @PostMapping("/{reviewId}/approve")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "admin.reviews.approve")
    public ResponseEntity<Void> approveReview(@PathVariable UUID reviewId) {
        Long adminId = resolveAdminId();
        adminReviewService.approveReview(reviewId, adminId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{reviewId}/block")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "admin.reviews.block")
    public ResponseEntity<Void> blockReview(
            @PathVariable UUID reviewId,
            @Valid @RequestBody BlockReviewRequest request) {
        Long adminId = resolveAdminId();
        adminReviewService.blockReview(reviewId, request.reason(), adminId);
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
