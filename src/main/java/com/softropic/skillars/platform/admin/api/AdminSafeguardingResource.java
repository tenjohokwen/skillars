package com.softropic.skillars.platform.admin.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.admin.contract.AdminSafeguardingReasonRequest;
import com.softropic.skillars.platform.admin.contract.AdminSafeguardingRecordDto;
import com.softropic.skillars.platform.admin.contract.AdminSafeguardingRequestDto;
import com.softropic.skillars.platform.admin.service.AdminSafeguardingService;
import com.softropic.skillars.platform.marketplace.service.CoachSafeguardingService;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * S-09: staff inspection of coach safeguarding-check requests. ROLE_ADMIN only - deliberately NOT
 * {@code SecurityConstants.HAS_ADMIN_ROLE}, which also admits ROLE_LTD_ADMIN: the uploads are criminal-record
 * certificates, so this surface is not widened to the limited admin role.
 */
@Validated
@RestController
@RequestMapping("/api/admin/safeguarding")
@RequiredArgsConstructor
@Observed(name = "admin.safeguarding")
public class AdminSafeguardingResource {

    /** ROLE_ADMIN only (not ROLE_LTD_ADMIN). */
    static final String FULL_ADMIN_ONLY = "hasRole('" + SecurityConstants.ROLE_ADMIN + "')";

    private final AdminSafeguardingService adminSafeguardingService;
    private final SecurityUtil securityUtil;

    @GetMapping("/requests")
    @PreAuthorize(FULL_ADMIN_ONLY)
    @Observed(name = "admin.safeguarding.requests")
    public ResponseEntity<Page<AdminSafeguardingRequestDto>> getPendingRequests(
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page) {
        return ResponseEntity.ok(adminSafeguardingService.getPendingRequests(page));
    }

    @GetMapping("/records")
    @PreAuthorize(FULL_ADMIN_ONLY)
    @Observed(name = "admin.safeguarding.records")
    public ResponseEntity<Page<AdminSafeguardingRecordDto>> getRecords(
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page) {
        return ResponseEntity.ok(adminSafeguardingService.getRecords(page));
    }

    /**
     * The decrypted certificate upload of a pending request. Audited per view; never cached. The content type is the
     * one sniffed from the file's magic bytes at upload (PDF, JPEG or PNG only), so the viewer is never handed
     * active content.
     */
    @GetMapping("/requests/{coachId}/document")
    @PreAuthorize(FULL_ADMIN_ONLY)
    @Observed(name = "admin.safeguarding.document")
    public ResponseEntity<byte[]> viewDocument(@PathVariable UUID coachId) {
        CoachSafeguardingService.Document document = adminSafeguardingService.viewDocument(coachId, resolveAdminId());
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(document.contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"safeguarding-document\"")
            .header("X-Content-Type-Options", "nosniff")
            .cacheControl(CacheControl.noStore())
            .body(document.bytes());
    }

    @PostMapping("/requests/{coachId}/inspect")
    @PreAuthorize(FULL_ADMIN_ONLY)
    @Observed(name = "admin.safeguarding.inspect")
    public ResponseEntity<AdminSafeguardingRecordDto> inspect(@PathVariable UUID coachId) {
        return ResponseEntity.ok(adminSafeguardingService.inspect(coachId, resolveAdminId()));
    }

    @PostMapping("/requests/{coachId}/reject")
    @PreAuthorize(FULL_ADMIN_ONLY)
    @Observed(name = "admin.safeguarding.reject")
    public ResponseEntity<Void> reject(@PathVariable UUID coachId,
                                       @Valid @RequestBody(required = false) AdminSafeguardingReasonRequest request) {
        adminSafeguardingService.reject(coachId, request == null ? null : request.reason(), resolveAdminId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/records/{coachId}")
    @PreAuthorize(FULL_ADMIN_ONLY)
    @Observed(name = "admin.safeguarding.revoke")
    public ResponseEntity<Void> revoke(@PathVariable UUID coachId,
                                       @Valid @RequestBody(required = false) AdminSafeguardingReasonRequest request) {
        adminSafeguardingService.revoke(coachId, request == null ? null : request.reason(), resolveAdminId());
        return ResponseEntity.noContent().build();
    }

    private Long resolveAdminId() {
        Object principal = securityUtil.getCurrentUser();
        if (!(principal instanceof Principal p)) {
            throw new InsufficientAuthenticationException("Unexpected principal type");
        }
        try {
            return Long.parseLong(p.getBusinessId());
        } catch (NumberFormatException e) {
            throw new InsufficientAuthenticationException("Invalid business ID");
        }
    }
}
