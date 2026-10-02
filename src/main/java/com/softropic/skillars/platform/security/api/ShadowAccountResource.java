package com.softropic.skillars.platform.security.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.contract.CreatePlayerProfileRequest;
import com.softropic.skillars.platform.security.contract.CreateSelfPlayerProfileRequest;
import com.softropic.skillars.platform.security.contract.PlayerProfileResponse;
import com.softropic.skillars.platform.security.contract.UpdatePlayerPositionRequest;
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
import org.springframework.web.bind.annotation.PatchMapping;
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
        Long parentId = securityUtil.requireCurrentUserId();
        PlayerProfileResponse response = shadowAccountService.createPlayerProfile(parentId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PreAuthorize(SecurityConstants.HAS_PLAYER_ROLE)
    @PostMapping("/me")
    public ResponseEntity<PlayerProfileResponse> createSelfOwnedPlayerProfile(
        @RequestBody @Valid CreateSelfPlayerProfileRequest request
    ) {
        Long userId = securityUtil.requireCurrentUserId();
        PlayerProfileResponse response = shadowAccountService.createSelfOwnedPlayerProfile(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PreAuthorize(SecurityConstants.HAS_PLAYER_ROLE)
    @GetMapping("/me")
    public ResponseEntity<PlayerProfileResponse> getSelfOwnedPlayerProfile() {
        Long userId = securityUtil.requireCurrentUserId();
        return ResponseEntity.ok(shadowAccountService.getSelfOwnedPlayerProfile(userId));
    }

    /**
     * AC4: the missing update counterpart to {@code POST /me} (one-time-only create). Returns the
     * updated {@link PlayerProfileResponse} body — the project's body-less-204 PATCH convention
     * applies to body-LESS success, and this response lets the "My Profile" dialog's {@code @updated}
     * handler re-fetch the same shape {@code GET /me} already returns.
     */
    @PreAuthorize(SecurityConstants.HAS_PLAYER_ROLE)
    @PatchMapping("/me/position")
    public ResponseEntity<PlayerProfileResponse> updateOwnPosition(
        @RequestBody @Valid UpdatePlayerPositionRequest request
    ) {
        Long userId = securityUtil.requireCurrentUserId();
        return ResponseEntity.ok(shadowAccountService.updateOwnPosition(userId, request.position()));
    }

    @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
    @GetMapping
    public ResponseEntity<List<PlayerProfileResponse>> listPlayerProfiles() {
        Long parentId = securityUtil.requireCurrentUserId();
        return ResponseEntity.ok(shadowAccountService.listPlayerProfiles(parentId));
    }

    @PreAuthorize("@playerOwnershipGuard.check(authentication, #playerId)")
    @GetMapping("/{playerId}")
    public ResponseEntity<PlayerProfileResponse> getPlayerProfile(@PathVariable Long playerId) {
        Long parentId = securityUtil.requireCurrentUserId();
        return ResponseEntity.ok(shadowAccountService.getPlayerProfile(playerId, parentId));
    }

    @PreAuthorize("@playerOwnershipGuard.check(authentication, #playerId)")
    @PostMapping("/{playerId}/link-parent")
    public ResponseEntity<Void> linkParent(@PathVariable Long playerId) {
        throw new ShadowAccountException("security.playerAlreadyHasParent", "Player already has a parent");
    }

    /**
     * AC5: lets a parent edit a child's position after initial creation — no route (frontend or
     * backend) existed for this before. Reuses the same ownership guard {@code getPlayerProfile}
     * already uses, which resolves the caller's own businessId as {@code parentId} and confirms
     * {@code findByIdAndParentId(playerId, parentId)} exists — no new SpEL, no new bean.
     */
    @PreAuthorize("@playerOwnershipGuard.check(authentication, #playerId)")
    @PatchMapping("/{playerId}/position")
    public ResponseEntity<PlayerProfileResponse> updateChildPosition(
        @PathVariable Long playerId,
        @RequestBody @Valid UpdatePlayerPositionRequest request
    ) {
        Long parentId = securityUtil.requireCurrentUserId();
        return ResponseEntity.ok(shadowAccountService.updateChildPosition(playerId, parentId, request.position()));
    }
}
