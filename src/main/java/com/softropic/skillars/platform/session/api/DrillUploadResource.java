package com.softropic.skillars.platform.session.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.session.contract.DrillUploadInitiateRequest;
import com.softropic.skillars.platform.session.contract.DrillUploadInitiateResponse;
import com.softropic.skillars.platform.session.service.DrillUploadService;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Observed(name = "session.drills.upload")
@RestController
@RequestMapping("/api/session/drills")
@RequiredArgsConstructor
public class DrillUploadResource {

    private final DrillUploadService drillUploadService;
    private final SecurityUtil securityUtil;

    @PostMapping("/{drillId}/video/initiate")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<DrillUploadInitiateResponse> initiateUpload(
        @PathVariable UUID drillId,
        @RequestBody @Valid DrillUploadInitiateRequest req
    ) {
        DrillUploadInitiateResponse resp = drillUploadService.initiateUpload(drillId, currentCoachUserId(), req);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @DeleteMapping("/{drillId}/video")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> deleteVideo(@PathVariable UUID drillId) {
        drillUploadService.deleteVideo(drillId, currentCoachUserId());
        return ResponseEntity.noContent().build();
    }

    private Long currentCoachUserId() {
        return securityUtil.getCurrentCoachUserId();
    }
}
