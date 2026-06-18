package com.softropic.skillars.platform.session.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.session.contract.HomeworkAssignmentResponse;
import com.softropic.skillars.platform.session.service.HomeworkAssignmentService;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Observed(name = "session.homework")
@RestController
@RequiredArgsConstructor
public class HomeworkResource {

    private final HomeworkAssignmentService homeworkAssignmentService;
    private final SecurityUtil securityUtil;

    @GetMapping("/api/session/players/{playerId}/homework")
    @PreAuthorize("@playerOwnershipGuard.check(authentication, #playerId)")
    public ResponseEntity<List<HomeworkAssignmentResponse>> getLockerRoomDrills(
            @PathVariable Long playerId) {
        return ResponseEntity.ok(homeworkAssignmentService.getLockerRoomDrills(playerId));
    }

    @PostMapping("/api/session/homework/{assignmentId}/complete")
    @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
    public ResponseEntity<Void> markComplete(@PathVariable UUID assignmentId) {
        Long parentId = securityUtil.getCurrentCoachUserId();
        homeworkAssignmentService.markComplete(assignmentId, parentId);
        return ResponseEntity.ok().build();
    }
}
