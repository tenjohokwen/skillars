package com.softropic.skillars.platform.session.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.session.contract.DrillResponse;
import com.softropic.skillars.platform.session.contract.InvalidParamException;
import com.softropic.skillars.platform.session.service.DrillLibraryService;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    public ResponseEntity<List<DrillResponse>> getDrills(@RequestParam(required = false) String library) {
        if (library == null || (!library.equals("PLATFORM") && !library.equals("PRIVATE"))) {
            throw new InvalidParamException("library");
        }
        if ("PLATFORM".equals(library)) {
            return ResponseEntity.ok(drillLibraryService.listPlatformDrills());
        }
        return ResponseEntity.ok(drillLibraryService.listPrivateDrills(securityUtil.getCurrentCoachUserId()));
    }

    @PostMapping("/{drillId}/clone")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<DrillResponse> cloneDrill(@PathVariable UUID drillId) {
        DrillResponse cloned = drillLibraryService.cloneDrill(drillId, securityUtil.getCurrentCoachUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(cloned);
    }
}
