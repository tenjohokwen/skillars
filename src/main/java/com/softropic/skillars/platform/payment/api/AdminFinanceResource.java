package com.softropic.skillars.platform.payment.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.payment.contract.AdminFinanceOverviewDto;
import com.softropic.skillars.platform.payment.contract.CoachRevenueAdminDto;
import com.softropic.skillars.platform.payment.service.CoachPayoutHoldRecoveryService;
import com.softropic.skillars.platform.payment.service.RevenueReportingService;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/admin/payment")
@RequiredArgsConstructor
@Observed(name = "payment.admin.finance")
@PreAuthorize(SecurityConstants.HAS_FULL_ADMIN_ROLE)
public class AdminFinanceResource {

    private final RevenueReportingService revenueReportingService;
    private final CoachPayoutHoldRecoveryService coachPayoutHoldRecoveryService;

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

    /**
     * fix/payment-payout-recovery: "release held payouts" — puts the coach's HOLD payouts back on the release
     * path (each re-checked for freezes, then one transfer enqueued). Replaces the runbook's hand-written
     * {@code UPDATE ... SET status = 'PENDING_RELEASE'}. Safe to repeat: a payout no longer in HOLD is skipped,
     * and one whose account still cannot receive goes back to HOLD (or retries) at transfer time.
     */
    @PostMapping("/coaches/{coachId}/payouts/release-held")
    public ResponseEntity<CoachPayoutHoldRecoveryService.Result> releaseHeldPayouts(@PathVariable UUID coachId) {
        CoachPayoutHoldRecoveryService.Result result =
            coachPayoutHoldRecoveryService.redriveHeld(coachId, false, "ADMIN");
        log.warn("[COACH_PAYOUT_HOLD_RECOVERY] coach={} — admin re-drive of held payouts: {} re-enqueued, {} still "
            + "frozen, {} skipped", coachId, result.released(), result.stillFrozen(), result.skipped());
        return ResponseEntity.ok(result);
    }

    private Instant toStartInstant(LocalDate date) {
        return date == null ? null : date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private Instant toEndInstant(LocalDate date) {
        return date == null ? null : date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
