package com.softropic.skillars.platform.booking.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.booking.contract.PurchaseSessionPackRequest;
import com.softropic.skillars.platform.booking.contract.SessionPackPurchasedResponse;
import com.softropic.skillars.platform.booking.service.SessionPackService;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Observed(name = "booking.sessionPacks")
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class SessionPackResource {

    private final SessionPackService sessionPackService;
    private final SecurityUtil securityUtil;

    @GetMapping("/players/{playerId}/packs")
    @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
    public ResponseEntity<List<SessionPackPurchasedResponse>> getPlayerPacks(
            @PathVariable Long playerId,
            @RequestParam(required = false) UUID coachId) {
        return ResponseEntity.ok(sessionPackService.getPacksForPlayer(currentParentId(), playerId, coachId));
    }

    @PostMapping("/players/{playerId}/packs/purchase")
    @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
    public ResponseEntity<SessionPackPurchasedResponse> purchasePack(
            @PathVariable Long playerId,
            @RequestBody @Valid PurchaseSessionPackRequest req) {
        Long parentId = currentParentId();
        SessionPackPurchasedResponse response = req.sessionPackId() != null
            ? sessionPackService.purchasePack(parentId, playerId, req.coachId(), req.sessionPackId())
            : sessionPackService.purchaseSingleSession(parentId, playerId, req.coachId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    private Long currentParentId() {
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
