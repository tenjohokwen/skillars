package com.softropic.skillars.platform.security.api;

import com.softropic.skillars.platform.security.contract.LoginRequest;
import com.softropic.skillars.platform.security.contract.LoginResponse;
import com.softropic.skillars.platform.security.service.AuthService;
import com.softropic.skillars.platform.security.service.LoginTokenManager;

import io.micrometer.observation.annotation.Observed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Observed(name = "security.auth")
@RequiredArgsConstructor
public class AuthResource {

    private final AuthService authService;
    private final LoginTokenManager loginTokenManager;

    @PostMapping("/login")
    @PreAuthorize("permitAll()")
    public ResponseEntity<LoginResponse> login(@RequestBody @Valid LoginRequest req,
                                               HttpServletRequest httpReq,
                                               HttpServletResponse res) {
        loginTokenManager.ensureClientHasPreLoginId();
        return ResponseEntity.ok(authService.login(req.email(), req.password(), httpReq.getRemoteAddr(), res));
    }

    @PostMapping("/refresh")
    @PreAuthorize("permitAll()")
    public ResponseEntity<LoginResponse> refresh(HttpServletRequest req, HttpServletResponse res) {
        return ResponseEntity.ok(authService.refresh(req, res));
    }

    @PostMapping("/logout")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Void> logout(HttpServletRequest req, HttpServletResponse res) {
        authService.logout(req, res);
        return ResponseEntity.noContent().build();
    }
}
