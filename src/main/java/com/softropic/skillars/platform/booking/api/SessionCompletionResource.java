package com.softropic.skillars.platform.booking.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.booking.contract.WrapUpRequest;
import com.softropic.skillars.platform.booking.service.BookingCompletionService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.session.contract.DrillResponse;
import com.softropic.skillars.platform.session.repo.SessionRepository;
import com.softropic.skillars.platform.session.service.DrillSuggestionService;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

@Observed(name = "booking.completion")
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
@Slf4j
public class SessionCompletionResource {

    private final BookingCompletionService bookingCompletionService;
    private final SecurityUtil securityUtil;
    private final SessionRepository sessionRepository;

    // @Lazy breaks circular dependency: platform.session imports platform.booking.contract,
    // and platform.booking.api now injects platform.session.service.DrillSuggestionService.
    @Autowired
    @Lazy
    private DrillSuggestionService drillSuggestionService;

    @PostMapping("/{id}/start")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> startSession(@PathVariable UUID id) {
        bookingCompletionService.startSession(id, currentUserId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/end")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> endSession(@PathVariable UUID id) {
        bookingCompletionService.endSession(id, currentUserId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/pause")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> pauseSession(@PathVariable UUID id) {
        bookingCompletionService.pauseSession(id, currentUserId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/resume")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> resumeSession(@PathVariable UUID id) {
        bookingCompletionService.resumeSession(id, currentUserId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> submitWrapUp(@PathVariable UUID id, @RequestBody @Valid WrapUpRequest request) {
        bookingCompletionService.submitWrapUp(id, currentUserId(), request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/quick-complete")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> initiateQuickComplete(@PathVariable UUID id) {
        bookingCompletionService.initiateQuickComplete(id, currentUserId());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/confirm-completion")
    @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
    public ResponseEntity<Void> confirmCompletion(@PathVariable UUID id) {
        bookingCompletionService.confirmCompletion(id, currentUserId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/session/{bookingId}/drills/suggestions")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<List<DrillResponse>> getDrillSuggestions(
            @PathVariable UUID bookingId,
            @RequestParam(defaultValue = "2") int limit) {
        return sessionRepository.findByBookingId(bookingId)
            .map(session -> ResponseEntity.ok(drillSuggestionService.suggest(session.getId(), currentUserId(), limit)))
            .orElseGet(() -> ResponseEntity.ok(List.of()));
    }

    private Long currentUserId() {
        return securityUtil.requireCurrentUserId();
    }
}
