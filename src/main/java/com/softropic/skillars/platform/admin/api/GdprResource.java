package com.softropic.skillars.platform.admin.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.admin.contract.GdprRequestCreatedResponse;
import com.softropic.skillars.platform.admin.service.GdprRequestService;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/gdpr")
@RequiredArgsConstructor
@Observed(name = "gdpr")
public class GdprResource {

    private final GdprRequestService gdprRequestService;
    private final SecurityUtil securityUtil;

    @PostMapping("/export")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    @Observed(name = "gdpr.requestExport")
    public ResponseEntity<GdprRequestCreatedResponse> requestExport() {
        UUID requestId = gdprRequestService.requestExport(resolveCurrentUserId());
        return ResponseEntity.accepted().body(new GdprRequestCreatedResponse(requestId));
    }

    @GetMapping("/export/{requestId}")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    @Observed(name = "gdpr.exportStatus")
    public ResponseEntity<?> getExportStatus(@PathVariable UUID requestId) {
        return gdprRequestService.getExportStatus(requestId, resolveCurrentUserId());
    }

    @PostMapping("/erasure")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    @Observed(name = "gdpr.requestErasure")
    public ResponseEntity<GdprRequestCreatedResponse> requestErasure() {
        UUID requestId = gdprRequestService.requestErasure(resolveCurrentUserId());
        return ResponseEntity.accepted().body(new GdprRequestCreatedResponse(requestId));
    }

    private Long resolveCurrentUserId() {
        return Long.parseLong(((Principal) securityUtil.getCurrentUser()).getBusinessId());
    }
}
