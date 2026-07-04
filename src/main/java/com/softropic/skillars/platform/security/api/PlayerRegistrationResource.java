package com.softropic.skillars.platform.security.api;

import com.softropic.skillars.platform.security.api.dto.ResendVerificationRequest;
import com.softropic.skillars.platform.security.api.dto.VerifyEmailResponse;
import com.softropic.skillars.platform.security.api.dto.VerifyPhoneRequest;
import com.softropic.skillars.platform.security.contract.PlayerRegistrationRequest;
import com.softropic.skillars.platform.security.service.PlayerRegistrationService;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Observed(name = "security.player_registration")
@RestController
@RequestMapping("/api/security/player")
@RequiredArgsConstructor
public class PlayerRegistrationResource {

    private final PlayerRegistrationService playerRegistrationService;

    @PreAuthorize("permitAll()")
    @PostMapping("/register")
    public ResponseEntity<Void> register(@RequestBody @Valid PlayerRegistrationRequest request) {
        playerRegistrationService.registerPlayer(request);
        return ResponseEntity.ok().build();
    }

    @PreAuthorize("permitAll()")
    @GetMapping("/verify-email")
    public ResponseEntity<VerifyEmailResponse> verifyEmail(@RequestParam UUID token) {
        return ResponseEntity.ok(playerRegistrationService.verifyEmail(token));
    }

    @PreAuthorize("permitAll()")
    @PostMapping("/verify-phone")
    public ResponseEntity<Void> verifyPhone(@RequestBody @Valid VerifyPhoneRequest request) {
        playerRegistrationService.verifyPhone(request.userId(), request.otp());
        return ResponseEntity.ok().build();
    }

    @PreAuthorize("permitAll()")
    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(@RequestBody @Valid ResendVerificationRequest request) {
        playerRegistrationService.resendVerificationEmail(request.email());
        return ResponseEntity.ok().build();
    }
}
