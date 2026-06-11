package com.softropic.skillars.platform.config.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.config.contract.ConfigValueResponse;
import com.softropic.skillars.platform.config.contract.UpdateConfigRequest;
import com.softropic.skillars.platform.config.service.ConfigService;

import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Observed(name = "config")
@RestController
@RequestMapping("/api/config")
public class ConfigResource {

    private final ConfigService configService;

    public ConfigResource(ConfigService configService) {
        this.configService = configService;
    }

    @GetMapping("/values/{key}")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    public ResponseEntity<ConfigValueResponse> getValue(@PathVariable String key) {
        return ResponseEntity.ok(configService.findResponse(key));
    }

    @PutMapping("/values/{key}")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    public ResponseEntity<ConfigValueResponse> updateValue(
            @PathVariable String key,
            @Valid @RequestBody UpdateConfigRequest request) {
        return ResponseEntity.ok(configService.updateConfig(key, request.value()));
    }
}
