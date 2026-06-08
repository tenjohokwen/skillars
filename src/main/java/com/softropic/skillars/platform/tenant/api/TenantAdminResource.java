package com.softropic.skillars.platform.tenant.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.tenant.contract.ApiKeyDto;
import com.softropic.skillars.platform.tenant.contract.ApiKeyEnvironment;
import com.softropic.skillars.platform.tenant.contract.TenantDetailDto;
import com.softropic.skillars.platform.tenant.contract.TenantDto;
import com.softropic.skillars.platform.tenant.contract.TenantStatus;
import com.softropic.skillars.platform.tenant.contract.TenantSummaryDto;
import com.softropic.skillars.platform.tenant.contract.WebhookSecretDto;
import com.softropic.skillars.platform.tenant.repo.Tenant;
import com.softropic.skillars.platform.tenant.repo.TenantRepository;
import com.softropic.skillars.platform.tenant.service.ApiKeyService;
import com.softropic.skillars.platform.tenant.service.TenantQueryService;
import com.softropic.skillars.platform.tenant.service.TenantService;

import io.micrometer.observation.annotation.Observed;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;


@Observed(name = "http.admin.tenants")
@RestController
@RequestMapping("/v1/admin/tenants")
public class TenantAdminResource {

    private final TenantService tenantService;
    private final ApiKeyService apiKeyService;
    private final TenantQueryService tenantQueryService;
    private final TenantRepository tenantRepository;

    public TenantAdminResource(TenantService tenantService, ApiKeyService apiKeyService,
                               TenantQueryService tenantQueryService, TenantRepository tenantRepository) {
        this.tenantService = tenantService;
        this.apiKeyService = apiKeyService;
        this.tenantQueryService = tenantQueryService;
        this.tenantRepository = tenantRepository;
    }

    // TENT-05: paginated tenant list with optional status filter
    @GetMapping
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    public ResponseEntity<Page<TenantSummaryDto>> listTenants(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        TenantStatus statusEnum = status != null ? TenantStatus.valueOf(status) : null;
        return ResponseEntity.ok(tenantQueryService.findAll(statusEnum, page, size));
    }

    // TENT-06: full tenant detail (no webhookSecret)
    @GetMapping("/{tenantRef}")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    public ResponseEntity<TenantDetailDto> getTenantDetail(@PathVariable String tenantRef) {
        return ResponseEntity.ok(tenantQueryService.findByTenantRef(tenantRef));
    }

    // WSEC-03: dedicated webhook secret endpoint
    @GetMapping("/{tenantRef}/webhook-secret")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    public ResponseEntity<WebhookSecretDto> getWebhookSecret(@PathVariable String tenantRef) {
        return ResponseEntity.ok(tenantQueryService.getWebhookSecret(tenantRef));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    public TenantCreationResponse createTenant(@Valid @RequestBody CreateTenantRequest request) {
        TenantService.TenantCreationResult result =
            tenantService.createTenant(request.name(), ApiKeyEnvironment.valueOf(request.environment()),
                                       request.email(), request.webhookUrl());

        TenantDto tenantDto = new TenantDto(
            result.tenant().getId(),
            result.tenant().getTenantRef(),
            result.tenant().getName(),
            result.tenant().getTenantStatus()
        );
        ApiKeyDto apiKeyDto = new ApiKeyDto(
            result.key().getId(),
            result.key().getKeyPrefix(),
            result.key().getEnvironment(),
            result.rawKey()   // shown exactly once — not stored
        );
        return new TenantCreationResponse(tenantDto, apiKeyDto);
    }

    @PostMapping("/{tenantId}/keys/{keyId}/rotate")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    public ApiKeyDto rotateKey(@PathVariable Long tenantId, @PathVariable Long keyId) {
        ApiKeyService.ApiKeyAndRawKey result = apiKeyService.rotate(keyId);
        return new ApiKeyDto(
            result.entity().getId(),
            result.entity().getKeyPrefix(),
            result.entity().getEnvironment(),
            result.rawKey()   // new raw key — shown once, never stored
        );
    }

    @DeleteMapping("/{tenantId}/keys/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    public void revokeKey(@PathVariable Long tenantId, @PathVariable Long keyId) {
        apiKeyService.revoke(keyId);
    }

    // NOTIF-04: reactivate a revoked API key
    @PostMapping("/{tenantId}/keys/{keyId}/reactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    public void reactivateKey(@PathVariable Long tenantId, @PathVariable Long keyId) {
        apiKeyService.reactivate(keyId);
    }

    // UI-01: generate a new API key for a given environment (requires no active key)
    @PostMapping("/{tenantRef}/keys/generate")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    public ApiKeyDto generateKey(@PathVariable String tenantRef,
                                 @RequestParam ApiKeyEnvironment env) {
        Tenant tenant = tenantRepository.findByTenantRef(tenantRef)
            .orElseThrow(() -> new EntityNotFoundException("Tenant not found: " + tenantRef));
        ApiKeyService.ApiKeyAndRawKey result = apiKeyService.generateAndStore(tenant, env);
        return new ApiKeyDto(
            result.entity().getId(),
            result.entity().getKeyPrefix(),
            result.entity().getEnvironment(),
            result.rawKey()
        );
    }

    // TENT-10: update tenant name
    @PatchMapping("/{tenantRef}/name")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    public void updateName(@PathVariable String tenantRef,
                           @Valid @RequestBody UpdateNameRequest request) {
        tenantService.updateName(tenantRef, request.name());
    }

    // TENT-02: update tenant email
    @PatchMapping("/{tenantRef}/email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    public void updateEmail(@PathVariable String tenantRef,
                            @Valid @RequestBody UpdateEmailRequest request) {
        tenantService.updateEmail(tenantRef, request.email());
    }

    // TENT-03: update tenant webhook URL
    @PatchMapping("/{tenantRef}/webhook-url")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    public void updateWebhookUrl(@PathVariable String tenantRef,
                                 @Valid @RequestBody UpdateWebhookUrlRequest request) {
        tenantService.updateWebhookUrl(tenantRef, request.webhookUrl());
    }

    // TENT-04: suspend tenant (atomically revokes all keys)
    @PostMapping("/{tenantRef}/suspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    public void suspend(@PathVariable String tenantRef) {
        tenantService.suspend(tenantRef);
    }

    // TENT-07: reactivate tenant (returns new PROD key)
    @PostMapping("/{tenantRef}/reactivate")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    public ApiKeyDto reactivate(@PathVariable String tenantRef) {
        ApiKeyService.ApiKeyAndRawKey result = tenantService.reactivate(tenantRef);
        return new ApiKeyDto(
            result.entity().getId(),
            result.entity().getKeyPrefix(),
            result.entity().getEnvironment(),
            result.rawKey()
        );
    }

    // TENT-08: regenerate webhook secret (returns 204; secret retrieved via GET)
    @PostMapping("/{tenantRef}/webhook-secret")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    public void regenerateWebhookSecret(@PathVariable String tenantRef) {
        tenantService.regenerateWebhookSecret(tenantRef);
    }

    public record CreateTenantRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Pattern(regexp = "PROD|DEV|SANDBOX", message = "environment must be PROD, DEV, or SANDBOX") String environment,
        @Email @Size(max = 255) String email,
        @Size(max = 2048) String webhookUrl
    ) {}

    public record TenantCreationResponse(TenantDto tenant, ApiKeyDto apiKey) {}

    public record UpdateNameRequest(@NotBlank @Size(max = 255) String name) {}
    public record UpdateEmailRequest(@NotBlank @Email String email) {}
    public record UpdateWebhookUrlRequest(@NotBlank @Size(max = 2048) String webhookUrl) {}
}
