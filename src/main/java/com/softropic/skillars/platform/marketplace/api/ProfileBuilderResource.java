package com.softropic.skillars.platform.marketplace.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.marketplace.contract.ProfileBuilderStatusResponse;
import com.softropic.skillars.platform.marketplace.contract.ProfileBuilderStep1Request;
import com.softropic.skillars.platform.marketplace.contract.ProfileBuilderStep2Request;
import com.softropic.skillars.platform.marketplace.contract.ProfileBuilderStep3Request;
import com.softropic.skillars.platform.marketplace.contract.ProfileBuilderStep4Request;
import com.softropic.skillars.platform.marketplace.contract.ProfileBuilderStep5Request;
import com.softropic.skillars.platform.marketplace.contract.ProfileBuilderStepResponse;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Observed(name = "marketplace.profile_builder")
@RestController
@RequestMapping("/api/marketplace/coaches/me/profile")
@RequiredArgsConstructor
@Slf4j
public class ProfileBuilderResource {

    private final CoachProfileService coachProfileService;
    private final SecurityUtil securityUtil;

    @GetMapping("/status")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<ProfileBuilderStatusResponse> getStatus() {
        return ResponseEntity.ok(coachProfileService.getBuilderStatus(currentUserId()));
    }

    @PutMapping("/steps/1")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<ProfileBuilderStepResponse> saveStep1(
            @RequestBody @Valid ProfileBuilderStep1Request req) {
        return ResponseEntity.ok(coachProfileService.saveStep1(currentUserId(), req));
    }

    @PutMapping("/steps/2")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<ProfileBuilderStepResponse> saveStep2(
            @RequestBody @Valid ProfileBuilderStep2Request req) {
        return ResponseEntity.ok(coachProfileService.saveStep2(currentUserId(), req));
    }

    @PutMapping("/steps/3")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<ProfileBuilderStepResponse> saveStep3(
            @RequestBody @Valid ProfileBuilderStep3Request req) {
        return ResponseEntity.ok(coachProfileService.saveStep3(currentUserId(), req));
    }

    @PutMapping("/steps/4")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<ProfileBuilderStepResponse> saveStep4(
            @RequestBody @Valid ProfileBuilderStep4Request req) {
        return ResponseEntity.ok(coachProfileService.saveStep4(currentUserId(), req));
    }

    @PutMapping("/steps/5")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<ProfileBuilderStepResponse> saveStep5(
            @RequestBody @Valid ProfileBuilderStep5Request req) {
        return ResponseEntity.ok(coachProfileService.saveStep5(currentUserId(), req));
    }

    @PostMapping("/publish")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<ProfileBuilderStatusResponse> publish() {
        return ResponseEntity.ok(coachProfileService.publishProfile(currentUserId()));
    }

    private Long currentUserId() {
        return Long.parseLong(((Principal) securityUtil.getCurrentUser()).getBusinessId());
    }
}
