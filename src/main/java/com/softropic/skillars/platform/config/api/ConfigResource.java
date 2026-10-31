package com.softropic.skillars.platform.config.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.config.contract.AdminConfigEntryDto;
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

import java.util.List;

@Observed(name = "config")
@RestController
@RequestMapping("/api/config")
public class ConfigResource {

    private final ConfigService configService;

    public ConfigResource(ConfigService configService) {
        this.configService = configService;
    }

    /**
     * Admin config editor (FR-ADM-006): every stored value plus every unseeded
     * {@code HAS_CODE_DEFAULT} key, each with the range {@link #updateValue} enforces.
     */
    @GetMapping("/values")
    @PreAuthorize(SecurityConstants.HAS_FULL_ADMIN_ROLE)
    @Observed(name = "config.admin.list")
    public ResponseEntity<List<AdminConfigEntryDto>> listValues() {
        return ResponseEntity.ok(configService.listForAdmin());
    }

    @GetMapping("/values/{key}")
    @PreAuthorize(SecurityConstants.HAS_FULL_ADMIN_ROLE)
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
     * <p><strong>Admin UI.</strong> The admin config editor ({@code src/frontend/src/pages/admin/
     * AdminConfigPage.vue}, route {@code /admin/config}) drives this endpoint and {@link #listValues}.
     * It surfaces the same {@code ConfigBounds} ranges this endpoint enforces, and shows the 400
     * reason returned here per field. This supersedes the {@code skillars-deferred-92} AC24.1 note
     * (2026-09-04) that there was no admin config UI.
     */
    @PutMapping("/values/{key}")
    @PreAuthorize(SecurityConstants.HAS_FULL_ADMIN_ROLE)
    public ResponseEntity<ConfigValueResponse> updateValue(
            @PathVariable String key,
            @Valid @RequestBody UpdateConfigRequest request) {
        return ResponseEntity.ok(configService.updateConfig(key, request.value()));
    }
}
