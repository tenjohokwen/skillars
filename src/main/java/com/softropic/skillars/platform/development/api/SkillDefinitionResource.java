package com.softropic.skillars.platform.development.api;

import com.softropic.skillars.platform.development.contract.SkillDefinitionDto;
import com.softropic.skillars.platform.development.service.SkillDefinitionService;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class SkillDefinitionResource {

    private final SkillDefinitionService skillDefinitionService;

    @GetMapping("/api/development/skill-definitions")
    @PreAuthorize("isAuthenticated()")
    @Observed(name = "development.skill-definitions")
    public ResponseEntity<List<SkillDefinitionDto>> getSkillDefinitions() {
        return ResponseEntity.ok(skillDefinitionService.getActiveSkillDefinitions());
    }
}
