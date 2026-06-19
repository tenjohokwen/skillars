package com.softropic.skillars.platform.development.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.development.contract.GenerateReportRequest;
import com.softropic.skillars.platform.development.contract.PerformanceReportResponse;
import com.softropic.skillars.platform.development.service.ReportGenerationService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class PerformanceReportResource {

    private final ReportGenerationService reportGenerationService;
    private final SecurityUtil securityUtil;

    @PostMapping("/api/development/players/{playerId}/reports")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    @Observed(name = "development.report.generate")
    public ResponseEntity<Void> generateReport(
            @PathVariable Long playerId,
            @RequestBody @Valid GenerateReportRequest request) {
        Long coachUserId = securityUtil.getCurrentCoachUserId();
        reportGenerationService.generateReport(coachUserId, playerId, request.nextSteps());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/development/players/{playerId}/reports")
    @PreAuthorize("hasRole('ROLE_COACH') or @playerOwnershipGuard.check(authentication, #playerId)")
    @Observed(name = "development.report.list")
    public ResponseEntity<List<PerformanceReportResponse>> listReports(@PathVariable Long playerId) {
        return ResponseEntity.ok(reportGenerationService.listReports(playerId));
    }
}
