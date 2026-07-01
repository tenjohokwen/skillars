package com.softropic.skillars.platform.video.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.marketplace.service.PlayerProfileService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.video.contract.VideoApprovalResponse;
import com.softropic.skillars.platform.video.repo.VideoApprovalRequest;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import com.softropic.skillars.platform.video.service.VideoApprovalService;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Observed(name = "video.approvals")
@RestController
@RequestMapping("/api/video/approvals")
@RequiredArgsConstructor
@Slf4j
public class VideoApprovalResource {

    private final VideoApprovalService videoApprovalService;
    private final SecurityUtil securityUtil;
    private final PlayerProfileService playerProfileService;
    private final VideoRepository videoRepository;

    @GetMapping
    @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
    public ResponseEntity<List<VideoApprovalResponse>> listPendingApprovals() {
        Long parentId = currentParentId();
        List<VideoApprovalRequest> requests = videoApprovalService.getPendingApprovalsForParent(parentId);
        List<VideoApprovalResponse> responses = requests.stream()
            .map(r -> {
                // TODO: batch player name resolution if list grows beyond single-family use case
                String playerName = playerProfileService.getPlayerNameByPlayerId(r.getPlayerId());
                if (playerName == null) {
                    playerName = "Player " + r.getPlayerId();
                }
                String videoType = videoRepository.findById(r.getVideoId())
                    .map(v -> v.getVideoType() != null ? v.getVideoType().name() : null)
                    .orElse(null);
                return new VideoApprovalResponse(
                    r.getId(), r.getVideoId(), r.getPlayerId(), playerName, videoType,
                    r.getStatus(), r.getCreatedAt());
            })
            .toList();
        return ResponseEntity.ok(responses);
    }

    @PutMapping("/{id}/approve")
    @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approveVideo(@PathVariable UUID id) {
        videoApprovalService.approveVideo(id, currentParentId());
    }

    @PutMapping("/{id}/reject")
    @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rejectVideo(@PathVariable UUID id) {
        videoApprovalService.rejectVideo(id, currentParentId());
    }

    private Long currentParentId() {
        return securityUtil.requireCurrentUserId();
    }
}
