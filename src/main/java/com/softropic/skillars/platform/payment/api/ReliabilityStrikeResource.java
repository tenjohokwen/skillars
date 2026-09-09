package com.softropic.skillars.platform.payment.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.marketplace.repo.CoachReliabilityStrike;
import com.softropic.skillars.platform.payment.contract.ReliabilityStrikeResponse;
import com.softropic.skillars.platform.payment.service.ReliabilityStrikeService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

import static org.springframework.data.domain.Sort.Direction.DESC;

@Observed(name = "payment.reliability")
@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
public class ReliabilityStrikeResource {

    private static final int MAX_PAGE_SIZE = 100;

    private final ReliabilityStrikeService reliabilityStrikeService;
    private final SecurityUtil securityUtil;

    @GetMapping("/coaches/me/strikes")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Page<ReliabilityStrikeResponse>> getMyStrikes(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        // skillars-deferred-103 P7: clamp caller-supplied paging so a negative page / zero size
        // cannot 500 via PageRequest.of, and a huge size cannot reinstate the unbounded read AC2
        // set out to remove.
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<CoachReliabilityStrike> strikes = reliabilityStrikeService.getCoachStrikes(
            currentCoachUserId(), PageRequest.of(safePage, safeSize, Sort.by(DESC, "createdAt")));
        Page<ReliabilityStrikeResponse> response = strikes.map(s -> new ReliabilityStrikeResponse(
            s.getId(),
            s.getBookingId(),
            s.getReason(),
            s.getCreatedAt().toInstant(),
            s.isAcknowledged()
        ));
        return ResponseEntity.ok(response);
    }

    @PutMapping("/coaches/strikes/{strikeId}/acknowledge")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> acknowledgeStrike(@PathVariable UUID strikeId) {
        reliabilityStrikeService.acknowledge(strikeId, currentCoachUserId());
        return ResponseEntity.noContent().build();
    }

    private Long currentCoachUserId() {
        return securityUtil.requireCurrentUserId();
    }
}
