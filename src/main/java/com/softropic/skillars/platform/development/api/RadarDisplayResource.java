package com.softropic.skillars.platform.development.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.development.contract.CoachRadarPreferenceRequest;
import com.softropic.skillars.platform.development.contract.CoachRadarPreferenceResponse;
import com.softropic.skillars.platform.development.contract.CorrelationResponse;
import com.softropic.skillars.platform.development.contract.RadarDisplayResponse;
import com.softropic.skillars.platform.development.service.DevelopmentCorrelationService;
import com.softropic.skillars.platform.development.service.RadarDisplayService;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class RadarDisplayResource {

    private final RadarDisplayService radarDisplayService;
    private final DevelopmentCorrelationService correlationService;
    private final SecurityUtil securityUtil;
    private final CoachProfileService coachProfileService;

    // Coach AND parent access — parent guarded via playerOwnershipGuard
    @GetMapping("/api/development/players/{playerId}/radar/display")
    @PreAuthorize("hasRole('ROLE_COACH') or @playerOwnershipGuard.check(authentication, #playerId)")
    @Observed(name = "development.radar.display")
    public ResponseEntity<RadarDisplayResponse> getRadarDisplay(@PathVariable Long playerId) {
        return ResponseEntity.ok(radarDisplayService.getRadarDisplay(playerId));
    }

    // Coach only — persists the selected skill subset per coach-player pair
    @GetMapping("/api/development/players/{playerId}/radar/preferences")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    @Observed(name = "development.radar.preferences.get")
    public ResponseEntity<CoachRadarPreferenceResponse> getPreferences(@PathVariable Long playerId) {
        UUID coachId = coachProfileService.getCoachIdByUserId(securityUtil.getCurrentCoachUserId());
        return ResponseEntity.ok(radarDisplayService.getPreferences(coachId, playerId));
    }

    // Coach only — 204 on success
    @PutMapping("/api/development/players/{playerId}/radar/preferences")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    @Observed(name = "development.radar.preferences.put")
    public ResponseEntity<Void> savePreferences(
            @PathVariable Long playerId,
            @RequestBody @Valid CoachRadarPreferenceRequest request) {
        UUID coachId = coachProfileService.getCoachIdByUserId(securityUtil.getCurrentCoachUserId());
        radarDisplayService.savePreferences(coachId, playerId, request);
        return ResponseEntity.noContent().build();
    }

    // Coach only — Academy gate enforced in service layer
    @GetMapping("/api/development/players/{playerId}/radar/correlation")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    @Observed(name = "development.radar.correlation")
    public ResponseEntity<CorrelationResponse> getCorrelation(@PathVariable Long playerId) {
        UUID coachId = coachProfileService.getCoachIdByUserId(securityUtil.getCurrentCoachUserId());
        return ResponseEntity.ok(correlationService.getInsights(playerId, coachId));
    }
}
