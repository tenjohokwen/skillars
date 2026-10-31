package com.softropic.skillars.platform.security.api;

import com.softropic.skillars.infrastructure.security.RateLimited;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.api.dto.AgeVerificationStartResponse;
import com.softropic.skillars.platform.security.api.dto.AgeVerificationStatusResponse;
import com.softropic.skillars.platform.security.service.AgeVerificationService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * S-27: the self-registered player's age-verification flow. Both endpoints act on the caller only (no id in the
 * path), so there is nothing to enumerate; the provider outcome arrives on the Stripe webhook, not here.
 */
@Observed(name = "security.age_verification")
@RestController
@RequestMapping("/api/security/age-verification")
@RequiredArgsConstructor
public class AgeVerificationResource {

    private final AgeVerificationService ageVerificationService;
    private final SecurityUtil securityUtil;

    @GetMapping
    @PreAuthorize(SecurityConstants.HAS_PLAYER_ROLE)
    public ResponseEntity<AgeVerificationStatusResponse> getStatus() {
        return ResponseEntity.ok(ageVerificationService.getStatus(securityUtil.requireCurrentUserId()));
    }

    // Per-IP burst bound; the real cost control is the per-user daily cap inside the service.
    @PostMapping("/session")
    @PreAuthorize(SecurityConstants.HAS_PLAYER_ROLE)
    @RateLimited(key = "age_verification_start", capacity = 5, duration = 10, unit = TimeUnit.MINUTES)
    public ResponseEntity<AgeVerificationStartResponse> start() {
        return ResponseEntity.ok(ageVerificationService.start(securityUtil.requireCurrentUserId()));
    }
}
