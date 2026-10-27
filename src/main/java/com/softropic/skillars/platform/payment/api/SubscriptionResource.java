package com.softropic.skillars.platform.payment.api;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.contract.CoachChangeTierRequest;
import com.softropic.skillars.platform.payment.contract.CoachSubscribeRequest;
import com.softropic.skillars.platform.payment.contract.CoachSubscriptionResponse;
import com.softropic.skillars.platform.payment.contract.PlayerChangeTierRequest;
import com.softropic.skillars.platform.payment.contract.PlayerSubscribeRequest;
import com.softropic.skillars.platform.payment.contract.PlayerSubscriptionResponse;
import com.softropic.skillars.platform.payment.contract.TierInfoResponse;
import com.softropic.skillars.platform.payment.service.SubscriptionService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Observed(name = "payment.subscription")
@RestController
@RequestMapping("/api/payment/subscriptions")
@RequiredArgsConstructor
public class SubscriptionResource {

    private final SubscriptionService subscriptionService;
    private final CoachProfileRepository coachProfileRepository;
    private final SecurityUtil securityUtil;

    // ─── Coach Tiers ─────────────────────────────────────────────────────────────

    @GetMapping("/coach/tiers")
    @PreAuthorize("permitAll()")
    public ResponseEntity<List<TierInfoResponse>> getCoachTiers() {
        return ResponseEntity.ok(subscriptionService.getCoachTiers());
    }

    @GetMapping("/coach/me")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<CoachSubscriptionResponse> getMyCoachSubscription() {
        UUID coachId = resolveCoachId();
        return ResponseEntity.ok(subscriptionService.getCoachSubscription(coachId));
    }

    @PostMapping("/coach/subscribe")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<CoachSubscriptionResponse> subscribeCoach(
            @Valid @RequestBody CoachSubscribeRequest request) {
        UUID coachId = resolveCoachId();
        Long coachUserId = securityUtil.getCurrentCoachUserId();
        CoachSubscriptionResponse response = subscriptionService.subscribeCoach(coachId, coachUserId, request.tier());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/coach/change-tier")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> changeCoachTier(@Valid @RequestBody CoachChangeTierRequest request) {
        UUID coachId = resolveCoachId();
        subscriptionService.changeCoachTier(coachId, request.newTier());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/coach")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> cancelCoachSubscription() {
        UUID coachId = resolveCoachId();
        subscriptionService.cancelCoachSubscription(coachId);
        return ResponseEntity.noContent().build();
    }

    // ─── Player Tiers ─────────────────────────────────────────────────────────────

    @GetMapping("/player/tiers")
    @PreAuthorize("permitAll()")
    public ResponseEntity<List<TierInfoResponse>> getPlayerTiers() {
        return ResponseEntity.ok(subscriptionService.getPlayerTiers());
    }

    @GetMapping("/player/me")
    @PreAuthorize("@playerOwnershipGuard.check(authentication, #playerId)")
    public ResponseEntity<PlayerSubscriptionResponse> getMyPlayerSubscription(
            @RequestParam Long playerId) {
        Long callerUserId = currentCallerUserId();
        return ResponseEntity.ok(subscriptionService.getPlayerSubscription(callerUserId, playerId));
    }

    // Player-subscription mutations are owner-scoped, not parent-only: the parent of a minor's
    // profile OR a self-registered adult player on their OWN profile (PlayerProfile.userId — such a
    // profile has no parent, chk_pp_owner) may subscribe / re-tier / cancel. PlayerOwnershipGuard
    // admits exactly those two callers; SubscriptionService.assertPlayerOwnership re-checks the same
    // pair in the service layer. Previously HAS_PARENT_ROLE, which left a self-registered player
    // with no way at all to manage the subscription they pay for. (A @Valid body is bound before
    // @PreAuthorize runs, so #request is safe to dereference here.)
    @PostMapping("/player/subscribe")
    @PreAuthorize("@playerOwnershipGuard.check(authentication, #request.playerId())")
    public ResponseEntity<PlayerSubscriptionResponse> subscribePlayer(
            @Valid @RequestBody PlayerSubscribeRequest request) {
        Long callerUserId = currentCallerUserId();
        PlayerSubscriptionResponse response = subscriptionService.subscribePlayer(
            callerUserId, request.playerId(), request.tier(), request.billingInterval());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/player/change-tier")
    @PreAuthorize("@playerOwnershipGuard.check(authentication, #request.playerId())")
    public ResponseEntity<Void> changePlayerTier(@Valid @RequestBody PlayerChangeTierRequest request) {
        Long callerUserId = currentCallerUserId();
        subscriptionService.changePlayerTier(callerUserId, request.playerId(), request.newTier());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/player")
    @PreAuthorize("@playerOwnershipGuard.check(authentication, #playerId)")
    public ResponseEntity<Void> cancelPlayerSubscription(@RequestParam Long playerId) {
        Long callerUserId = currentCallerUserId();
        subscriptionService.cancelPlayerSubscription(callerUserId, playerId);
        return ResponseEntity.noContent().build();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private UUID resolveCoachId() {
        Long coachUserId = securityUtil.getCurrentCoachUserId();
        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
        return coach.getId();
    }

    /** The caller's user id — a parent's, or a self-registered player's own (Principal.businessId). */
    private Long currentCallerUserId() {
        return securityUtil.requireCurrentUserId();
    }
}
