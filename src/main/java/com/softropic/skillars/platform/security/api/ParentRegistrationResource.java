package com.softropic.skillars.platform.security.api;

import com.softropic.skillars.platform.security.api.dto.ResendOtpRequest;
import com.softropic.skillars.platform.security.api.dto.ResendVerificationRequest;
import com.softropic.skillars.platform.security.api.dto.VerifyEmailResponse;
import com.softropic.skillars.platform.security.api.dto.VerifyPhoneRequest;
import com.softropic.skillars.platform.security.contract.ParentRegistrationRequest;
import com.softropic.skillars.platform.security.service.ParentRegistrationService;
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

@Observed(name = "security.parent_registration")
@RestController
@RequestMapping("/api/security/parent")
@RequiredArgsConstructor
public class ParentRegistrationResource {

    private final ParentRegistrationService parentRegistrationService;

    @PreAuthorize("permitAll()")
    @PostMapping("/register")
    public ResponseEntity<Void> register(@RequestBody @Valid ParentRegistrationRequest request) {
        parentRegistrationService.registerParent(request);
        return ResponseEntity.ok().build();
    }

    @PreAuthorize("permitAll()")
    @GetMapping("/verify-email")
    public ResponseEntity<VerifyEmailResponse> verifyEmail(@RequestParam UUID token) {
        return ResponseEntity.ok(parentRegistrationService.verifyEmail(token));
    }

    @PreAuthorize("permitAll()")
    @PostMapping("/verify-phone")
    public ResponseEntity<Void> verifyPhone(@RequestBody @Valid VerifyPhoneRequest request) {
        parentRegistrationService.verifyPhone(request.userId(), request.otp());
        return ResponseEntity.ok().build();
    }

    @PreAuthorize("permitAll()")
    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(@RequestBody @Valid ResendVerificationRequest request) {
        parentRegistrationService.resendVerificationEmail(request.email());
        return ResponseEntity.ok().build();
    }

    @PreAuthorize("permitAll()")
    @PostMapping("/resend-otp")
    public ResponseEntity<Void> resendOtp(@RequestBody @Valid ResendOtpRequest request) {
        parentRegistrationService.resendPhoneOtp(request.userId());
        return ResponseEntity.ok().build();
    }
}
