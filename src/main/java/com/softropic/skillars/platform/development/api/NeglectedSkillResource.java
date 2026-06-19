package com.softropic.skillars.platform.development.api;

import com.softropic.skillars.platform.development.contract.NeglectedSkillResponse;
import com.softropic.skillars.platform.development.service.SluTargetService;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class NeglectedSkillResource {

    private final SluTargetService sluTargetService;

    @GetMapping("/api/development/players/{playerId}/neglected-skills")
    @PreAuthorize("hasRole('ROLE_COACH') or @playerOwnershipGuard.check(authentication, #playerId)")
    @Observed(name = "development.neglected-skills")
    public ResponseEntity<List<NeglectedSkillResponse>> getNeglectedSkills(@PathVariable Long playerId) {
        return ResponseEntity.ok(sluTargetService.getNeglectedSkills(playerId));
    }
}
