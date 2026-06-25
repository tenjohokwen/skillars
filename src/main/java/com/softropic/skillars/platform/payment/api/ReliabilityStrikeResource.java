package com.softropic.skillars.platform.payment.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.marketplace.repo.CoachReliabilityStrike;
import com.softropic.skillars.platform.payment.contract.ReliabilityStrikeResponse;
import com.softropic.skillars.platform.payment.service.ReliabilityStrikeService;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Observed(name = "payment.reliability")
@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
public class ReliabilityStrikeResource {

    private final ReliabilityStrikeService reliabilityStrikeService;
    private final SecurityUtil securityUtil;

    @GetMapping("/coaches/me/strikes")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<List<ReliabilityStrikeResponse>> getMyStrikes() {
        List<CoachReliabilityStrike> strikes = reliabilityStrikeService.getCoachStrikes(currentCoachUserId());
        List<ReliabilityStrikeResponse> response = strikes.stream()
            .map(s -> new ReliabilityStrikeResponse(
                s.getId(),
                s.getBookingId(),
                s.getReason(),
                s.getCreatedAt().toInstant(),
                s.isAcknowledged()
            ))
            .toList();
        return ResponseEntity.ok(response);
    }

    @PutMapping("/coaches/strikes/{strikeId}/acknowledge")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> acknowledgeStrike(@PathVariable UUID strikeId) {
        reliabilityStrikeService.acknowledge(strikeId, currentCoachUserId());
        return ResponseEntity.noContent().build();
    }

    private Long currentCoachUserId() {
        String businessId = ((Principal) securityUtil.getCurrentUser()).getBusinessId();
        if (businessId == null || businessId.isBlank()) {
            throw new InsufficientAuthenticationException("Principal has no business ID");
        }
        try {
            return Long.parseLong(businessId);
        } catch (NumberFormatException e) {
            throw new InsufficientAuthenticationException("Invalid business ID format in principal");
        }
    }
}
