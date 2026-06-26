package com.softropic.skillars.platform.payment.api;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.contract.CreditStatementEntryDto;
import com.softropic.skillars.platform.payment.contract.ParentReceiptDto;
import com.softropic.skillars.platform.payment.contract.ReceiptDto;
import com.softropic.skillars.platform.payment.contract.RevenueSummaryDto;
import com.softropic.skillars.platform.payment.contract.TransactionDto;
import com.softropic.skillars.platform.payment.service.RevenueReportingService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
@Observed(name = "payment.revenue")
public class RevenueResource {

    private final RevenueReportingService revenueReportingService;
    private final CoachProfileRepository coachProfileRepository;
    private final SecurityUtil securityUtil;

    @GetMapping("/coaches/me/revenue")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<RevenueSummaryDto> getCoachRevenueSummary(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        UUID coachId = resolveCoachId();
        return ResponseEntity.ok(revenueReportingService.getCoachRevenueSummary(
            coachId, toStartInstant(from), toEndInstant(to)));
    }

    @GetMapping("/coaches/me/transactions")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Page<TransactionDto>> getCoachTransactions(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID coachId = resolveCoachId();
        return ResponseEntity.ok(revenueReportingService.getCoachTransactions(
            coachId, toStartInstant(from), toEndInstant(to), PageRequest.of(page, size)));
    }

    @GetMapping("/coaches/bookings/{bookingId}/receipt")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<ReceiptDto> getCoachReceipt(@PathVariable UUID bookingId) {
        UUID coachId = resolveCoachId();
        return ResponseEntity.ok(revenueReportingService.getCoachReceipt(coachId, bookingId));
    }

    @GetMapping("/parents/bookings/{bookingId}/receipt")
    @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
    public ResponseEntity<ParentReceiptDto> getParentReceipt(@PathVariable UUID bookingId) {
        Long parentId = resolveParentId();
        return ResponseEntity.ok(revenueReportingService.getParentReceipt(parentId, bookingId));
    }

    @GetMapping("/credits/statement")
    @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
    public ResponseEntity<Page<CreditStatementEntryDto>> getCreditStatement(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long parentId = resolveParentId();
        return ResponseEntity.ok(revenueReportingService.getCreditStatement(
            parentId, toStartInstant(from), toEndInstant(to), PageRequest.of(page, size)));
    }

    private UUID resolveCoachId() {
        Long coachUserId = securityUtil.getCurrentCoachUserId();
        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
        return coach.getId();
    }

    private Long resolveParentId() {
        return securityUtil.getCurrentCoachUserId();
    }

    private Instant toStartInstant(LocalDate date) {
        return date == null ? null : date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private Instant toEndInstant(LocalDate date) {
        return date == null ? null : date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
