package com.softropic.skillars.platform.payment.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.payment.contract.AdminFinanceOverviewDto;
import com.softropic.skillars.platform.payment.contract.CoachRevenueAdminDto;
import com.softropic.skillars.platform.payment.service.RevenueReportingService;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/payment")
@RequiredArgsConstructor
@Observed(name = "payment.admin.finance")
@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
public class AdminFinanceResource {

    private final RevenueReportingService revenueReportingService;

    @GetMapping("/overview")
    public ResponseEntity<AdminFinanceOverviewDto> getOverview(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return ResponseEntity.ok(revenueReportingService.getAdminOverview(
            toStartInstant(from), toEndInstant(to)));
    }

    @GetMapping("/coaches/{coachId}/revenue")
    public ResponseEntity<CoachRevenueAdminDto> getCoachRevenue(@PathVariable UUID coachId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return ResponseEntity.ok(revenueReportingService.getAdminCoachRevenue(
            coachId, toStartInstant(from), toEndInstant(to)));
    }

    private Instant toStartInstant(LocalDate date) {
        return date == null ? null : date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private Instant toEndInstant(LocalDate date) {
        return date == null ? null : date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
