package com.softropic.skillars.platform.payment.api;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.config.PaymentProperties;
import com.softropic.skillars.platform.payment.contract.CoachStripeStatusResponse;
import com.softropic.skillars.platform.payment.contract.StripeOnboardingUrlResponse;
import com.softropic.skillars.platform.payment.service.StripeOnboardingService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/payment")
@Slf4j
@RequiredArgsConstructor
@Observed(name = "payment.stripe.onboarding")
public class StripeOnboardingResource {

    private final StripeOnboardingService onboardingService;
    private final CoachProfileRepository coachProfileRepository;
    private final PaymentProperties paymentProperties;
    private final SecurityUtil securityUtil;

    @GetMapping("/coaches/me/stripe/onboard")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<StripeOnboardingUrlResponse> getOnboardingUrl() {
        UUID coachId = resolveCoachId();
        String url = onboardingService.generateOnboardingUrl(coachId);
        return ResponseEntity.ok(new StripeOnboardingUrlResponse(url));
    }

    @GetMapping("/coaches/me/stripe/callback")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public void handleOAuthCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            @RequestParam(required = false) String state,
            HttpServletResponse response) throws IOException {
        if (error != null) {
            log.warn("[STRIPE_OAUTH_DENIED coachId=unknown error={}]", error);
            // P19: URL-encode attacker-controlled error value before appending to Location header
            response.sendRedirect(paymentProperties.getCallbackSuccessUrl()
                + "?stripe_error=" + URLEncoder.encode(error, StandardCharsets.UTF_8));
            return;
        }
        if (code == null || code.isBlank()) {
            response.sendRedirect(paymentProperties.getCallbackSuccessUrl() + "?stripe_error=missing_code");
            return;
        }
        UUID coachId = resolveCoachId();
        // P18: Validate state param matches authenticated coach to prevent CSRF account-linking
        if (state == null || !coachId.toString().equals(state)) {
            log.warn("[STRIPE_OAUTH_STATE_MISMATCH coachId={} state={}]", coachId, state);
            response.sendRedirect(paymentProperties.getCallbackSuccessUrl() + "?stripe_error=state_mismatch");
            return;
        }
        onboardingService.handleOAuthCallback(coachId, code);
        response.sendRedirect(paymentProperties.getCallbackSuccessUrl());
    }

    @GetMapping("/coaches/me/stripe/status")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<CoachStripeStatusResponse> getStripeStatus() {
        UUID coachId = resolveCoachId();
        return ResponseEntity.ok(onboardingService.getStripeStatus(coachId));
    }

    private UUID resolveCoachId() {
        Long coachUserId = securityUtil.getCurrentCoachUserId();
        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
        return coach.getId();
    }
}
