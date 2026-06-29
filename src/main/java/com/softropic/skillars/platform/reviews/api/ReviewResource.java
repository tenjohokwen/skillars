package com.softropic.skillars.platform.reviews.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.reviews.contract.CoachResponseRequest;
import com.softropic.skillars.platform.reviews.contract.SubmitReviewRequest;
import com.softropic.skillars.platform.reviews.contract.SubmitReviewResponse;
import com.softropic.skillars.platform.reviews.service.ReviewSubmissionService;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
@Observed(name = "reviews")
public class ReviewResource {

    private final ReviewSubmissionService reviewSubmissionService;
    private final CoachProfileService coachProfileService;
    private final SecurityUtil securityUtil;

    @PostMapping("/coaches/{coachId}")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    @Observed(name = "reviews.submit")
    public ResponseEntity<SubmitReviewResponse> submitReview(
            @PathVariable UUID coachId,
            @Valid @RequestBody SubmitReviewRequest request,
            Authentication auth) {
        Long userId = resolveUserId();
        String role = resolveRole(auth);
        SubmitReviewResponse response = reviewSubmissionService.submitReview(
            coachId, userId, role, request.rating(), request.body());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{reviewId}")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    @Observed(name = "reviews.update")
    public ResponseEntity<Void> updateReview(
            @PathVariable UUID reviewId,
            @Valid @RequestBody SubmitReviewRequest request,
            Authentication auth) {
        Long userId = resolveUserId();
        reviewSubmissionService.updateReview(reviewId, userId, request.rating(), request.body());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{reviewId}/response")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    @Observed(name = "reviews.coachResponse")
    public ResponseEntity<Void> submitCoachResponse(
            @PathVariable UUID reviewId,
            @Valid @RequestBody CoachResponseRequest request,
            Authentication auth) {
        Long userId = resolveUserId();
        UUID coachId = coachProfileService.getCoachIdByUserId(userId);
        reviewSubmissionService.submitCoachResponse(reviewId, coachId, request.body());
        return ResponseEntity.noContent().build();
    }

    private Long resolveUserId() {
        Object principal = securityUtil.getCurrentUser();
        if (!(principal instanceof Principal p)) {
            throw new InsufficientAuthenticationException("Unexpected principal type");
        }
        try {
            return Long.parseLong(p.getBusinessId());
        } catch (NumberFormatException e) {
            throw new InsufficientAuthenticationException("Invalid business ID format in principal");
        }
    }

    private String resolveRole(Authentication auth) {
        if (auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_COACH"))) return "COACH";
        if (auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_PARENT"))) return "PARENT";
        return "PLAYER";
    }
}
