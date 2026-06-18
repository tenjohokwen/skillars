package com.softropic.skillars.platform.session.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.session.contract.CreateSessionPlanRequest;
import com.softropic.skillars.platform.session.contract.SessionPlanResponse;
import com.softropic.skillars.platform.session.contract.UpdateSessionPlanRequest;
import com.softropic.skillars.platform.session.service.SessionPlanService;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Observed(name = "session.builder")
@RestController
@RequestMapping("/api/session/sessions")
@RequiredArgsConstructor
public class SessionBuilderResource {

    private final SessionPlanService sessionPlanService;
    private final SecurityUtil securityUtil;

    @PostMapping
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<SessionPlanResponse> createSession(
        @RequestBody @Valid CreateSessionPlanRequest req
    ) {
        SessionPlanResponse resp = sessionPlanService.createSession(req, currentCoachUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @PutMapping("/{sessionId}")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<SessionPlanResponse> updateSession(
        @PathVariable UUID sessionId,
        @RequestBody @Valid UpdateSessionPlanRequest req
    ) {
        return ResponseEntity.ok(sessionPlanService.updateSession(sessionId, req, currentCoachUserId()));
    }

    @GetMapping("/{sessionId}")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<SessionPlanResponse> getSession(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(sessionPlanService.getSession(sessionId, currentCoachUserId()));
    }

    @GetMapping("/by-booking/{bookingId}")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<SessionPlanResponse> getByBooking(@PathVariable UUID bookingId) {
        return sessionPlanService.findByBookingId(bookingId, currentCoachUserId())
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Long currentCoachUserId() {
        return securityUtil.getCurrentCoachUserId();
    }
}
