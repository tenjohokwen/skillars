package com.softropic.skillars.platform.development.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.development.contract.SessionPrepResponse;
import com.softropic.skillars.platform.development.service.SessionPrepService;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The Session Prep Card's backing endpoint — read-only, authorized by booking ownership rather
 * than the coach-player relationship check {@code /neglected-skills}/{@code /slu/coach-contributions}
 * are missing (see Dev Notes "Why this endpoint doesn't call..." in skillars-12-3). skillars-12-3
 * AC3/AC4.
 */
@RestController
@RequiredArgsConstructor
public class SessionPrepResource {

    private final SessionPrepService sessionPrepService;
    private final SecurityUtil securityUtil;
    private final CoachProfileService coachProfileService;

    @GetMapping("/api/development/bookings/{bookingId}/prep")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    @Observed(name = "development.session-prep.get")
    public ResponseEntity<SessionPrepResponse> getPrep(@PathVariable UUID bookingId) {
        UUID coachId = coachProfileService.getCoachIdByUserId(securityUtil.getCurrentCoachUserId());
        return ResponseEntity.ok(sessionPrepService.getPrep(bookingId, coachId));
    }
}
