package com.softropic.skillars.platform.session.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.session.contract.CoachHomeworkAssignmentResponse;
import com.softropic.skillars.platform.session.service.CoachHomeworkQueryService;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Coach view of the homework they assigned a player, with completion state. The coach↔player
 * relationship (shared skillars-12-10 access window) is enforced in {@link CoachHomeworkQueryService}.
 */
@Observed(name = "session.homework.coach")
@RestController
@RequiredArgsConstructor
public class CoachHomeworkResource {

    private final CoachHomeworkQueryService coachHomeworkQueryService;
    private final SecurityUtil securityUtil;

    @GetMapping("/api/session/coach/players/{playerId}/homework")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<List<CoachHomeworkAssignmentResponse>> getPlayerHomework(@PathVariable Long playerId) {
        return ResponseEntity.ok(
            coachHomeworkQueryService.listForCoach(securityUtil.getCurrentCoachUserId(), playerId));
    }
}
