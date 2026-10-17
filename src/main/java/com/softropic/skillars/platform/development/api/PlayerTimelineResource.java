package com.softropic.skillars.platform.development.api;

import com.softropic.skillars.platform.development.contract.TimelineResponse;
import com.softropic.skillars.platform.development.service.TimelineQueryService;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequiredArgsConstructor
public class PlayerTimelineResource {

    private final TimelineQueryService timelineQueryService;
    private final SecurityUtil securityUtil;
    private final CoachProfileService coachProfileService;

    @GetMapping("/api/development/players/{playerId}/timeline")
    @PreAuthorize("hasRole('ROLE_COACH') or @playerOwnershipGuard.check(authentication, #playerId)")
    @Observed(name = "development.timeline.get")
    public ResponseEntity<TimelineResponse> getTimeline(
            @PathVariable Long playerId,
            @RequestParam(defaultValue = "0")  @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        boolean isCoach = securityUtil.getCurrentUserRoles().contains("ROLE_COACH");
        UUID coachId = null;
        if (isCoach) {
            coachId = coachProfileService.getCoachIdByUserId(securityUtil.getCurrentCoachUserId());
        }
        return ResponseEntity.ok(timelineQueryService.getTimeline(playerId, isCoach, coachId, page, size));
    }
}
