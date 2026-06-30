package com.softropic.skillars.platform.admin.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.admin.contract.AdminGdprRequestDto;
import com.softropic.skillars.platform.admin.service.GdprRequestService;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/gdpr")
@RequiredArgsConstructor
@Observed(name = "admin.gdpr")
public class AdminGdprResource {

    private final GdprRequestService gdprRequestService;

    @GetMapping("/requests")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "admin.gdpr.list")
    public ResponseEntity<Page<AdminGdprRequestDto>> listRequests(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            Pageable pageable) {
        return ResponseEntity.ok(gdprRequestService.listRequests(type, status, pageable));
    }
}
