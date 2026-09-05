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
     *       {@code app.config.cache-ttl-seconds} (default 300s / 5 minutes), on every node.</strong>
     *       {@code ConfigService} holds an in-memory cache refreshed by
     *       {@code @Scheduled(fixedDelayString = "${app.config.cache-ttl-seconds:300}",
     *       timeUnit = TimeUnit.SECONDS)}, and a direct DB write cannot invalidate it. The value looks
     *       like it did not take effect.</li>
     * </ul>
     *
     * <p>That second case is what made {@code booking.session.defaultDurationMinutes} read as broken
     * during UAT. It is not a bug and the TTL is deliberately unchanged — a shorter TTL means more
     * polling for a table that changes a few times a year. <strong>Prefer this endpoint</strong> — not
     * because it reaches every node (it does not: {@code invalidate()} only clears the cache of the
     * node that handled this request, and a load-balanced retry can land on a different node each
     * time), but because it validates the input and is audited. If you must edit the database
     * directly, or need every node correct immediately rather than after the TTL, wait out the TTL or
     * restart the application — on more than one node, each caches independently and there is no
     * fleet-wide invalidation broadcast.
     *
     * <p>Also in {@code docs/deployment/runbook.md} § "Config change appears to have no effect".
     *
     * <p><strong>There is no admin config UI and no generated API docs</strong> — no springdoc/OpenAPI
     * dependency exists in this project, and {@code src/frontend} has no admin config page. This
     * javadoc, {@code getValue} above and the runbook section it points to are the entire "admin
     * config surface": an admin operator drives this endpoint directly (curl, Postman, an internal
     * tool), not through any UI shipped by this application. Recorded here per the
     * {@code skillars-deferred-92} AC24.1 code review (2026-09-04) — the AC allowed either an admin
     * UI or API docs as its deliverable, and this codebase has neither, which is a legitimate scope
     * outcome but was previously unstated.
     */
    @PutMapping("/values/{key}")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    public ResponseEntity<ConfigValueResponse> updateValue(
            @PathVariable String key,
            @Valid @RequestBody UpdateConfigRequest request) {
        return ResponseEntity.ok(configService.updateConfig(key, request.value()));
    }
}
