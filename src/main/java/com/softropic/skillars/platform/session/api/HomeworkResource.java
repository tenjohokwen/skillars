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
    // The locker room (and its "mark done" toggle) is shown to the player themselves as well as the
    // parent, so both roles are admitted here; ownership of the assignment's player profile — parent
    // of a minor's profile OR the self-registered player owning their own — is enforced in the
    // service against the assignment row, since the path carries an assignmentId, not a playerId.
    @PreAuthorize(SecurityConstants.HAS_PARENT_OR_PLAYER_ROLE)
    public ResponseEntity<Void> markComplete(@PathVariable UUID assignmentId) {
        Long callerUserId = securityUtil.requireCurrentUserId();
        homeworkAssignmentService.markComplete(assignmentId, callerUserId);
        return ResponseEntity.ok().build();
    }
}
