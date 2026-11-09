package com.softropic.skillars.platform.notification.api;

import com.softropic.skillars.platform.notification.repo.AlertRule;
import com.softropic.skillars.platform.notification.repo.AlertRuleRepository;
import com.softropic.skillars.platform.notification.service.AlertRuleCache;
import com.softropic.skillars.infrastructure.security.SecurityConstants;

import io.micrometer.observation.annotation.Observed;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * Admin REST endpoints for managing alert rules.
 *
 * <p>All endpoints require JWT authentication with ROLE_ADMIN (S-28: not ROLE_LTD_ADMIN).
 * These are automatically excluded from the API-key filter chain via the
 * NegatedRequestMatcher on /v1/admin/** in TenantSecurityConfig.
 *
 * <p>POST /v1/admin/alerts       — create a new alert rule
 * GET  /v1/admin/alerts          — list all currently enabled alert rules (from cache)
 * PUT  /v1/admin/alerts/{id}     — update an existing alert rule in place
 */
@Observed(name = "http.admin.alerts")
@RestController
@RequestMapping("/v1/admin/alerts")
@PreAuthorize(SecurityConstants.HAS_FULL_ADMIN_ROLE)
@RequiredArgsConstructor
public class AlertRuleAdminResource {

    private final AlertRuleRepository alertRuleRepository;
    private final AlertRuleCache alertRuleCache;

    /**
     * DTO for creating or updating an alert rule.
     *
     * @param metricName          metric to evaluate: FAILURE_RATE, FRAUD_SPIKE_RATE, CALLBACK_ANOMALY
     * @param threshold           breach threshold as a ratio in [0.0, 1.0]
     * @param windowSeconds       evaluation window duration in seconds
     * @param notificationChannel delivery channel: LOG or EMAIL (default LOG)
     * @param enabled             whether the rule is active (default true)
     * @param description         human-readable description
     */
    public record AlertRuleRequest(
            @NotBlank @Size(max = 100) String metricName,
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") @Digits(integer = 6, fraction = 4) BigDecimal threshold,
            @Positive int windowSeconds,
            @Pattern(regexp = "LOG|EMAIL") String notificationChannel,
            boolean enabled,
            @Size(max = 500) String description) {

        /** Compact canonical constructor — apply defaults for optional fields. */
        public AlertRuleRequest {
            if (notificationChannel == null || notificationChannel.isBlank()) {
                notificationChannel = "LOG";
            }
        }
    }

    /**
     * Create a new alert rule.
     *
     * @param request alert rule parameters
     * @return 201 with saved entity
     */
    @PostMapping
    public ResponseEntity<AlertRule> create(@Valid @RequestBody AlertRuleRequest request) {
        AlertRule rule = AlertRule.builder()
                .metricName(request.metricName())
                .threshold(request.threshold())
                .windowSeconds(request.windowSeconds())
                .notificationChannel(request.notificationChannel())
                .enabled(request.enabled())
                .description(request.description())
                .build();
        AlertRule saved = alertRuleRepository.save(rule);
        alertRuleCache.refresh();
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * List all currently enabled alert rules.
     * Returns the live cache snapshot — always current without a DB round-trip.
     *
     * @return 200 with list of enabled rules
     */
    @GetMapping
    public ResponseEntity<List<AlertRule>> list() {
        return ResponseEntity.ok(alertRuleCache.getCachedRules());
    }

    /**
     * Update an existing alert rule in place: one UPDATE of the same row, so the id is kept and a failed save
     * leaves the old rule as it was.
     *
     * @param id      ID of the rule to update
     * @param request replacement rule parameters
     * @return 200 with updated entity
     */
    @PutMapping("/{id}")
    public ResponseEntity<AlertRule> update(@PathVariable Long id,
                                            @Valid @RequestBody AlertRuleRequest request) {
        AlertRule rule = alertRuleRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("AlertRule not found: " + id));
        rule.apply(request.metricName(), request.threshold(), request.windowSeconds(),
                request.notificationChannel(), request.enabled(), request.description());
        AlertRule saved = alertRuleRepository.save(rule);
        alertRuleCache.refresh();
        return ResponseEntity.ok(saved);
    }
}
