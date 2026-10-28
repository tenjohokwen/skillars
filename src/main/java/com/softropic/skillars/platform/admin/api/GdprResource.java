package com.softropic.skillars.platform.admin.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.admin.contract.GdprRequestCreatedResponse;
import com.softropic.skillars.platform.admin.contract.GdprRequestStatusDto;
import com.softropic.skillars.platform.admin.service.GdprRequestService;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/gdpr")
@RequiredArgsConstructor
@Observed(name = "gdpr")
public class GdprResource {

    /**
     * Parent-only, and only for a child the caller owns as PARENT. The ownership guard also admits a
     * self-registered player acting on their OWN profile; GdprRequestService additionally requires
     * {@code PlayerProfile.parentId == caller}, so that case is still refused (403).
     */
    private static final String PARENT_OF_CHILD =
        SecurityConstants.HAS_PARENT_ROLE + " and @playerOwnershipGuard.check(authentication, #playerId)";

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

    /** The caller's own export/erasure requests (newest first) — drives the profile "Privacy & data" card. */
    @GetMapping("/requests")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    @Observed(name = "gdpr.listOwnRequests")
    public ResponseEntity<List<GdprRequestStatusDto>> listOwnRequests() {
        return ResponseEntity.ok(gdprRequestService.listOwnRequests(resolveCurrentUserId()));
    }

    @PostMapping("/players/{playerId}/export")
    @PreAuthorize(PARENT_OF_CHILD)
    @Observed(name = "gdpr.requestChildExport")
    public ResponseEntity<GdprRequestCreatedResponse> requestChildExport(@PathVariable Long playerId) {
        UUID requestId = gdprRequestService.requestChildExport(resolveCurrentUserId(), playerId);
        return ResponseEntity.accepted().body(new GdprRequestCreatedResponse(requestId));
    }

    @PostMapping("/players/{playerId}/erasure")
    @PreAuthorize(PARENT_OF_CHILD)
    @Observed(name = "gdpr.requestChildErasure")
    public ResponseEntity<GdprRequestCreatedResponse> requestChildErasure(@PathVariable Long playerId) {
        UUID requestId = gdprRequestService.requestChildErasure(resolveCurrentUserId(), playerId);
        return ResponseEntity.accepted().body(new GdprRequestCreatedResponse(requestId));
    }

    @GetMapping("/players/{playerId}/requests")
    @PreAuthorize(PARENT_OF_CHILD)
    @Observed(name = "gdpr.listChildRequests")
    public ResponseEntity<List<GdprRequestStatusDto>> listChildRequests(@PathVariable Long playerId) {
        return ResponseEntity.ok(gdprRequestService.listChildRequests(resolveCurrentUserId(), playerId));
    }

    private Long resolveCurrentUserId() {
        return securityUtil.requireCurrentUserId();
    }
}
