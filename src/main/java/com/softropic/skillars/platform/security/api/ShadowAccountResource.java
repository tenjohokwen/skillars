package com.softropic.skillars.platform.security.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.contract.CreatePlayerProfileRequest;
import com.softropic.skillars.platform.security.contract.PlayerProfileResponse;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.contract.exception.ShadowAccountException;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.security.service.ShadowAccountService;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Observed(name = "security.shadow_account")
@RestController
@RequestMapping("/api/security/players")
@RequiredArgsConstructor
public class ShadowAccountResource {

    private final ShadowAccountService shadowAccountService;
    private final SecurityUtil securityUtil;

    @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
    @PostMapping
    public ResponseEntity<PlayerProfileResponse> createPlayerProfile(
        @RequestBody @Valid CreatePlayerProfileRequest request
    ) {
        Long parentId = Long.parseLong(((Principal) securityUtil.getCurrentUser()).getBusinessId());
        PlayerProfileResponse response = shadowAccountService.createPlayerProfile(parentId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
    @GetMapping
    public ResponseEntity<List<PlayerProfileResponse>> listPlayerProfiles() {
        Long parentId = Long.parseLong(((Principal) securityUtil.getCurrentUser()).getBusinessId());
        return ResponseEntity.ok(shadowAccountService.listPlayerProfiles(parentId));
    }

    @PreAuthorize("@playerOwnershipGuard.check(authentication, #playerId)")
    @GetMapping("/{playerId}")
    public ResponseEntity<PlayerProfileResponse> getPlayerProfile(@PathVariable Long playerId) {
        Long parentId = Long.parseLong(((Principal) securityUtil.getCurrentUser()).getBusinessId());
        return ResponseEntity.ok(shadowAccountService.getPlayerProfile(playerId, parentId));
    }

    @PreAuthorize("@playerOwnershipGuard.check(authentication, #playerId)")
    @PostMapping("/{playerId}/link-parent")
    public ResponseEntity<Void> linkParent(@PathVariable Long playerId) {
        throw new ShadowAccountException("security.playerAlreadyHasParent", "Player already has a parent");
    }
}
