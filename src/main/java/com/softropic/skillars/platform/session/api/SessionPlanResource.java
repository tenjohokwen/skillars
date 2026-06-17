package com.softropic.skillars.platform.session.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.session.service.DrillLibraryService;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Observed(name = "session.plans")
@RestController
@RequestMapping("/api/session/plans")
@RequiredArgsConstructor
public class SessionPlanResource {

    private final DrillLibraryService drillLibraryService;
    private final SecurityUtil securityUtil;

    @PostMapping
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> createSessionPlan() {
        drillLibraryService.checkSessionBuilderGate(securityUtil.getCurrentCoachUserId());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
