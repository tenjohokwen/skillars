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

    /**
     * Updates one platform config value.
     *
     * <p><strong>Two paths change a config value, and they behave differently</strong>
     * (skillars-deferred-92 AC24; project-owner decision 6 was to document this, not change the TTL):
     *
     * <ul>
     *   <li><strong>Through this endpoint — immediate.</strong> {@code ConfigService.updateConfig}
     *       invalidates the cache as part of the write, so the new value is live on this node before
     *       the response returns.</li>
     *   <li><strong>Directly in the database (psql, a migration, a manual fix) — up to
     *       {@code app.config.cache-ttl-seconds} (default 300s / 5 minutes).</strong>
     *       {@code ConfigService} holds an in-memory cache refreshed by
     *       {@code @Scheduled(fixedDelayString = "${app.config.cache-ttl-seconds:300}")}, and a direct
     *       DB write cannot invalidate it. The value looks like it did not take effect.</li>
     * </ul>
     *
     * <p>That second case is what made {@code booking.session.defaultDurationMinutes} read as broken
     * during UAT. It is not a bug and the TTL is deliberately unchanged — a shorter TTL means more
     * polling for a table that changes a few times a year. <strong>Prefer this endpoint.</strong> If
     * you must edit the database directly, wait out the TTL or restart the app; on more than one node,
     * each caches independently, so wait for the slowest.
     *
     * <p>Also in {@code docs/deployment/runbook.md} § "Config change appears to have no effect".
     */
    @PutMapping("/values/{key}")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    public ResponseEntity<ConfigValueResponse> updateValue(
            @PathVariable String key,
            @Valid @RequestBody UpdateConfigRequest request) {
        return ResponseEntity.ok(configService.updateConfig(key, request.value()));
    }
}
