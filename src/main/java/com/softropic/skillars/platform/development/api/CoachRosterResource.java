package com.softropic.skillars.platform.development.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.development.contract.RosterResponse;
import com.softropic.skillars.platform.development.service.CoachRosterService;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * "My Players" roster — a zero-path-param, coach's-own-resource endpoint by construction (AC7): it
 * can never be used to enumerate another coach's players. Mirrors {@link CoachBrandingResource}'s
 * {@code me/} shape. skillars-12-1.
 */
@RestController
@RequiredArgsConstructor
public class CoachRosterResource {

    private final CoachRosterService coachRosterService;
    private final SecurityUtil securityUtil;
    private final CoachProfileService coachProfileService;

    @GetMapping("/api/development/coaches/me/roster")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    @Observed(name = "development.roster.get")
    public ResponseEntity<RosterResponse> getRoster() {
        UUID coachId = coachProfileService.getCoachIdByUserId(securityUtil.getCurrentCoachUserId());
        return ResponseEntity.ok(coachRosterService.getRoster(coachId));
    }
}
