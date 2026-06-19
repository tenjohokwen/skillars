package com.softropic.skillars.platform.development.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.development.contract.RadarAssessmentListResponse;
import com.softropic.skillars.platform.development.contract.RadarAssessmentRequest;
import com.softropic.skillars.platform.development.service.RadarAssessmentService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RadarAssessmentResource {

    private final RadarAssessmentService radarAssessmentService;
    private final SecurityUtil securityUtil;

    @PostMapping("/api/development/players/{playerId}/radar/entries")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    @Observed(name = "development.radar.submit")
    public ResponseEntity<Void> submitAssessment(
            @PathVariable Long playerId,
            @RequestBody @Valid RadarAssessmentRequest request) {
        radarAssessmentService.submitAssessment(securityUtil.getCurrentCoachUserId(), playerId, request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/development/players/{playerId}/radar/entries")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    @Observed(name = "development.radar.entries")
    public ResponseEntity<RadarAssessmentListResponse> getMyEntries(@PathVariable Long playerId) {
        return ResponseEntity.ok(
            radarAssessmentService.getMyEntries(securityUtil.getCurrentCoachUserId(), playerId));
    }
}
