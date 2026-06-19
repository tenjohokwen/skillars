package com.softropic.skillars.platform.development.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.development.contract.CoachBrandingRequest;
import com.softropic.skillars.platform.development.contract.CoachBrandingResponse;
import com.softropic.skillars.platform.development.service.ReportGenerationService;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class CoachBrandingResource {

    private final ReportGenerationService reportGenerationService;
    private final SecurityUtil securityUtil;
    private final CoachProfileService coachProfileService;

    @GetMapping("/api/development/coaches/me/branding")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    @Observed(name = "development.branding.get")
    public ResponseEntity<CoachBrandingResponse> getBranding() {
        UUID coachId = coachProfileService.getCoachIdByUserId(securityUtil.getCurrentCoachUserId());
        return ResponseEntity.ok(reportGenerationService.getBranding(coachId));
    }

    @PutMapping("/api/development/coaches/me/branding")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    @Observed(name = "development.branding.put")
    public ResponseEntity<Void> saveBranding(@RequestBody @Valid CoachBrandingRequest request) {
        UUID coachId = coachProfileService.getCoachIdByUserId(securityUtil.getCurrentCoachUserId());
        reportGenerationService.saveBranding(coachId, request);
        return ResponseEntity.noContent().build();
    }
}
