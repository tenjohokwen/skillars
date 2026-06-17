package com.softropic.skillars.platform.session.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.session.contract.DrillResponse;
import com.softropic.skillars.platform.session.contract.DrillTagRequest;
import com.softropic.skillars.platform.session.contract.InvalidParamException;
import com.softropic.skillars.platform.session.service.DrillLibraryService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Observed(name = "session.drills")
@RestController
@RequestMapping("/api/session/drills")
@RequiredArgsConstructor
public class DrillLibraryResource {

    private final DrillLibraryService drillLibraryService;
    private final SecurityUtil securityUtil;

    @GetMapping
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<List<DrillResponse>> getDrills(
        @RequestParam String library,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String skill,
        @RequestParam(required = false) String difficultyTier,
        @RequestParam(required = false) List<String> equipment,
        @RequestParam(required = false) Boolean weakFootBias
    ) {
        if (!library.equals("PLATFORM") && !library.equals("PRIVATE")) {
            throw new InvalidParamException("library");
        }
        return ResponseEntity.ok(drillLibraryService.listDrills(
            library, q, skill, difficultyTier, equipment, weakFootBias, currentCoachUserId()
        ));
    }

    @PostMapping("/{drillId}/clone")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<DrillResponse> cloneDrill(@PathVariable UUID drillId) {
        DrillResponse cloned = drillLibraryService.cloneDrill(drillId, currentCoachUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(cloned);
    }

    @PostMapping("/{drillId}/tags")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> addTag(@PathVariable UUID drillId, @RequestBody @Valid DrillTagRequest req) {
        drillLibraryService.addTag(drillId, req.tag(), currentCoachUserId());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/{drillId}/tags/{tag}")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> removeTag(@PathVariable UUID drillId, @PathVariable String tag) {
        drillLibraryService.removeTag(drillId, tag, currentCoachUserId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/tags/suggestions")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<List<String>> getSuggestedTags() {
        return ResponseEntity.ok(drillLibraryService.getSuggestedTags(currentCoachUserId()));
    }

    private Long currentCoachUserId() {
        return securityUtil.getCurrentCoachUserId();
    }
}
