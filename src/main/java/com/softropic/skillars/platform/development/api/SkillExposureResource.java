package com.softropic.skillars.platform.development.api;

import com.softropic.skillars.platform.development.contract.CoachContributionDto;
import com.softropic.skillars.platform.development.contract.NarrativeKeyDto;
import com.softropic.skillars.platform.development.contract.SkillExposureResponse;
import com.softropic.skillars.platform.development.service.SluContributionService;
import com.softropic.skillars.platform.development.service.SluDashboardService;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class SkillExposureResource {

    private final SluDashboardService sluDashboardService;
    private final SluContributionService sluContributionService;

    @GetMapping("/api/development/players/{playerId}/exposure")
    @PreAuthorize("hasRole('ROLE_COACH') or @playerOwnershipGuard.check(authentication, #playerId)")
    @Observed(name = "development.exposure")
    public ResponseEntity<SkillExposureResponse> getExposure(
            @PathVariable Long playerId,
            @RequestParam(defaultValue = "8") int weeks) {
        int weeksBack = Math.min(Math.max(weeks, 1), 52);
        return ResponseEntity.ok(sluDashboardService.getWeeklyExposure(playerId, weeksBack));
    }

    @GetMapping("/api/development/players/{playerId}/narrative")
    @PreAuthorize("hasRole('ROLE_COACH') or @playerOwnershipGuard.check(authentication, #playerId)")
    @Observed(name = "development.narrative")
    public ResponseEntity<List<NarrativeKeyDto>> getNarrative(@PathVariable Long playerId) {
        return ResponseEntity.ok(sluDashboardService.getNarrativeSummary(playerId));
    }

    @GetMapping("/api/development/players/{playerId}/slu/coach-contributions")
    @PreAuthorize("hasRole('ROLE_COACH') or @playerOwnershipGuard.check(authentication, #playerId)")
    @Observed(name = "development.slu.contributions")
    public ResponseEntity<List<CoachContributionDto>> getCoachContributions(
            @PathVariable Long playerId,
            @RequestParam(defaultValue = "30") int days) {
        int daysBack = Math.min(Math.max(days, 1), 3650);
        Instant since = Instant.now().minus(daysBack, ChronoUnit.DAYS);
        return ResponseEntity.ok(sluContributionService.getCoachContributions(playerId, since));
    }
}
