package com.softropic.skillars.platform.video.api;

import com.softropic.skillars.platform.marketplace.service.PlayerProfileService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.video.contract.VideoApprovalResponse;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoApprovalRequest;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import com.softropic.skillars.platform.video.service.VideoApprovalService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-90 AC13: {@code listPendingApprovals()} must resolve player names and video
 * types in a FIXED number of queries — one {@code findAllById} each — never one lookup per row.
 */
@ExtendWith(MockitoExtension.class)
class VideoApprovalResourceTest {

    @Mock private VideoApprovalService videoApprovalService;
    @Mock private SecurityUtil securityUtil;
    @Mock private PlayerProfileService playerProfileService;
    @Mock private VideoRepository videoRepository;

    private static VideoApprovalRequest request(UUID videoId, Long playerId) {
        VideoApprovalRequest r = new VideoApprovalRequest();
        r.setId(UUID.randomUUID());
        r.setVideoId(videoId);
        r.setPlayerId(playerId);
        r.setStatus("PENDING");
        r.setCreatedAt(Instant.now());
        return r;
    }

    private static Video video(UUID id) {
        Video v = new Video();
        v.setId(id);
        return v;
    }

    @Test
    void listPendingApprovals_batchesPlayerNamesAndVideoTypes() {
        Long parentId = 42L;
        when(securityUtil.requireCurrentUserId()).thenReturn(parentId);

        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();
        UUID v3 = UUID.randomUUID();
        when(videoApprovalService.getPendingApprovalsForParent(parentId))
            .thenReturn(List.of(request(v1, 1L), request(v2, 2L), request(v3, 3L)));
        when(playerProfileService.getPlayerNamesByPlayerIds(any()))
            .thenReturn(Map.of(1L, "Ann", 2L, "Bo", 3L, "Cy"));
        when(videoRepository.findAllById(any())).thenReturn(List.of(video(v1), video(v2), video(v3)));

        ResponseEntity<List<VideoApprovalResponse>> response = new VideoApprovalResource(
            videoApprovalService, securityUtil, playerProfileService, videoRepository).listPendingApprovals();

        assertThat(response.getBody()).hasSize(3);
        assertThat(response.getBody()).extracting(VideoApprovalResponse::playerName)
            .containsExactlyInAnyOrder("Ann", "Bo", "Cy");
        verify(videoRepository, times(1)).findAllById(any());
        verify(videoRepository, never()).findById(any());
        verify(playerProfileService, times(1)).getPlayerNamesByPlayerIds(any());
        verify(playerProfileService, never()).getPlayerNameByPlayerId(any());
    }
}
