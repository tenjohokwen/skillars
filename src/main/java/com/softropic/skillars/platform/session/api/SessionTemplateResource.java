package com.softropic.skillars.platform.session.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.booking.contract.PlannableBookingSummary;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.session.contract.CreateTemplateRequest;
import com.softropic.skillars.platform.session.contract.RenameTemplateRequest;
import com.softropic.skillars.platform.session.contract.SessionPlanResponse;
import com.softropic.skillars.platform.session.contract.SessionTemplateResponse;
import com.softropic.skillars.platform.session.service.SessionTemplateService;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Observed(name = "session.templates")
@RestController
@RequestMapping("/api/session/templates")
@RequiredArgsConstructor
public class SessionTemplateResource {

    private final SessionTemplateService sessionTemplateService;
    private final SecurityUtil securityUtil;

    @GetMapping
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<List<SessionTemplateResponse>> listTemplates() {
        return ResponseEntity.ok(sessionTemplateService.listTemplates(currentCoachUserId()));
    }

    @PostMapping
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<SessionTemplateResponse> createTemplate(
        @RequestBody @Valid CreateTemplateRequest req
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(sessionTemplateService.createTemplate(req, currentCoachUserId()));
    }

    @PutMapping("/{templateId}")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> renameTemplate(
        @PathVariable UUID templateId,
        @RequestBody @Valid RenameTemplateRequest req
    ) {
        sessionTemplateService.renameTemplate(templateId, req, currentCoachUserId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{templateId}")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> deleteTemplate(@PathVariable UUID templateId) {
        sessionTemplateService.deleteTemplate(templateId, currentCoachUserId());
        return ResponseEntity.noContent().build();
    }

    /** feat/coach-workflow-gaps A19: candidate bookings for the "Use for this session" picker. */
    @GetMapping("/deployable-bookings")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<List<PlannableBookingSummary>> listDeployableBookings() {
        return ResponseEntity.ok(sessionTemplateService.listDeployableBookings(currentCoachUserId()));
    }

    @PostMapping("/{templateId}/deploy")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<SessionPlanResponse> deployTemplate(
        @PathVariable UUID templateId,
        @RequestParam UUID bookingId
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(sessionTemplateService.deployTemplate(templateId, bookingId, currentCoachUserId()));
    }

    private Long currentCoachUserId() {
        return securityUtil.getCurrentCoachUserId();
    }
}
