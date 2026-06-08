package com.softropic.skillars.e2e.builder;

import com.softropic.skillars.platform.tenant.contract.ApiKeyEnvironment;
import com.softropic.skillars.platform.tenant.repo.Tenant;
import com.softropic.skillars.platform.tenant.repo.TenantRepository;
import com.softropic.skillars.platform.tenant.service.TenantService;
import com.softropic.skillars.platform.tenant.service.TenantService.TenantCreationResult;

/**
 * Fluent builder for creating Tenant test data (BUILD-01).
 *
 * <p>Delegates to {@link TenantService#createTenant(String, ApiKeyEnvironment)} so API key hashing
 * is handled by production code — no hand-rolled SHA-256.
 *
 * <p>If webhookUrl or webhookSecret are set, the Tenant entity is updated and saved via
 * TenantRepository after the initial creation.
 *
 * <p>Usage:
 * <pre>
 *     TenantBuilder.TenantCreatedResult tenant = new TenantBuilder()
 *         .withName("Acme Corp")
 *         .withWebhookUrl("https://example.com/hook", "my-secret")
 *         .create(tenantService, tenantRepository);
 *     // tenant.rawApiKey() is the value for the Authorization: ApiKey header
 * </pre>
 */
public class TenantBuilder {

    private String name = "Test Tenant";
    private String webhookUrl = null;
    private String webhookSecret = null;
    private ApiKeyEnvironment environment = ApiKeyEnvironment.PROD;

    public TenantBuilder withName(String name) {
        this.name = name;
        return this;
    }

    public TenantBuilder withWebhookUrl(String url) {
        this.webhookUrl = url;
        return this;
    }

    public TenantBuilder withWebhookUrl(String url, String secret) {
        this.webhookUrl = url;
        this.webhookSecret = secret;
        return this;
    }

    public TenantBuilder withEnvironment(ApiKeyEnvironment environment) {
        this.environment = environment;
        return this;
    }

    /**
     * Creates the tenant and its initial API key via production services.
     *
     * <p>If webhookUrl is configured, the tenant entity is updated and saved after creation.
     * Returns a {@link CreatedTenant} containing the tenantId, tenantRef, and raw API key
     * for use in HTTP {@code Authorization: ApiKey <rawApiKey>} headers.
     */
    public CreatedTenant create(TenantService tenantService, TenantRepository tenantRepository) {
        TenantCreationResult result = tenantService.createTenant(name, environment);
        Tenant tenant = result.tenant();

        if (webhookUrl != null || webhookSecret != null) {
            tenant.setWebhookUrl(webhookUrl);
            tenant.setWebhookSecret(webhookSecret);
            tenantRepository.save(tenant);
        }

        return new CreatedTenant(tenant.getId(), tenant.getTenantRef(), result.rawKey());
    }

    /**
     * Result of tenant creation — carries the IDs and credentials needed by E2E tests.
     *
     * @param tenantId   database PK (Long)
     * @param tenantRef  UUID string — the Loki-queryable tenant identifier
     * @param rawApiKey  unhashed key to set as {@code Authorization: ApiKey <rawApiKey>} header
     */
    public record CreatedTenant(Long tenantId, String tenantRef, String rawApiKey) {}
}
