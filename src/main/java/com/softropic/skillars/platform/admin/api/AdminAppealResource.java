package com.softropic.skillars.platform.admin.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.admin.contract.AdminAppealDto;
import com.softropic.skillars.platform.admin.contract.AppealStatus;
import com.softropic.skillars.platform.admin.contract.DecideAppealRequest;
import com.softropic.skillars.platform.admin.service.AppealService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/appeals")
@RequiredArgsConstructor
@Observed(name = "admin.appeals")
public class AdminAppealResource {

    private final AppealService appealService;
    private final SecurityUtil securityUtil;

    // Literal "/queue" outranks "/{appealId}" in Spring's path-pattern comparator — no ambiguity.
    @GetMapping("/queue")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "admin.appeals.queue")
    public ResponseEntity<Page<AdminAppealDto>> getQueue(
            @RequestParam(defaultValue = "OPEN") AppealStatus status,
            @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(appealService.getQueue(status, page));
    }

    @GetMapping("/{appealId}")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "admin.appeals.detail")
    public ResponseEntity<AdminAppealDto> getDetail(@PathVariable UUID appealId) {
        return ResponseEntity.ok(appealService.getDetail(appealId));
    }

    @PostMapping("/{appealId}/decide")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "admin.appeals.decide")
    public ResponseEntity<AdminAppealDto> decide(
            @PathVariable UUID appealId,
            @Valid @RequestBody DecideAppealRequest request) {
        return ResponseEntity.ok(appealService.decide(
            appealId, request.outcome(), request.note(), securityUtil.requireCurrentUserId()));
    }
}
