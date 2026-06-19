package com.softropic.skillars.platform.development.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.development.contract.SluTargetRequest;
import com.softropic.skillars.platform.development.contract.SluTargetResponse;
import com.softropic.skillars.platform.development.service.SluTargetService;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class SluTargetResource {

    private final SluTargetService sluTargetService;
    private final CoachProfileService coachProfileService;
    private final SecurityUtil securityUtil;

    @GetMapping("/api/development/players/{playerId}/targets")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    @Observed(name = "development.targets.get")
    public ResponseEntity<List<SluTargetResponse>> getTargets(@PathVariable Long playerId) {
        UUID coachId = coachProfileService.getCoachIdByUserId(securityUtil.getCurrentCoachUserId());
        return ResponseEntity.ok(sluTargetService.getTargets(coachId, playerId));
    }

    @PutMapping("/api/development/players/{playerId}/targets")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    @Observed(name = "development.targets.set")
    public ResponseEntity<List<SluTargetResponse>> setTargets(
            @PathVariable Long playerId,
            @RequestBody @Valid List<SluTargetRequest> requests) {
        UUID coachId = coachProfileService.getCoachIdByUserId(securityUtil.getCurrentCoachUserId());
        return ResponseEntity.ok(sluTargetService.setTargets(coachId, playerId, requests));
    }
}
