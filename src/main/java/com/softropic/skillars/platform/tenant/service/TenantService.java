package com.softropic.skillars.platform.tenant.service;

import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.tenant.contract.ApiKeyEnvironment;
import com.softropic.skillars.platform.tenant.contract.TenantStatus;
import com.softropic.skillars.platform.tenant.contract.event.TenantApiKeyEvent;
import com.softropic.skillars.platform.tenant.contract.event.TenantCreatedEvent;
import com.softropic.skillars.platform.tenant.contract.event.TenantStatusChangedEvent;
import com.softropic.skillars.platform.tenant.contract.event.TenantWebhookSecretRegeneratedEvent;
import com.softropic.skillars.platform.tenant.repo.Tenant;
import com.softropic.skillars.platform.tenant.repo.TenantApiKey;
import com.softropic.skillars.platform.tenant.repo.TenantApiKeyRepository;
import com.softropic.skillars.platform.tenant.repo.TenantRepository;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;


@Service
@Transactional
public class TenantService {

    private final TenantRepository tenantRepository;
    private final ApiKeyService apiKeyService;
    private final TenantApiKeyRepository keyRepository;
    private final ApplicationEventPublisher publisher;

    public TenantService(TenantRepository tenantRepository, ApiKeyService apiKeyService,
                         TenantApiKeyRepository keyRepository, ApplicationEventPublisher publisher) {
        this.tenantRepository = tenantRepository;
        this.apiKeyService = apiKeyService;
        this.keyRepository = keyRepository;
        this.publisher = publisher;
    }

    static String deriveKeyPrefix(String name) {
        if (name == null || name.isBlank()) return "UNK";
        String trimmed = name.trim().toUpperCase();
        if (trimmed.length() >= 3) return trimmed.substring(0, 3);
        if (trimmed.length() == 2) return trimmed + "0";
        return trimmed + "00";
    }

    private Tenant findTenantOrThrow(String tenantRef) {
        return tenantRepository.findByTenantRef(tenantRef)
            .orElseThrow(() -> new EntityNotFoundException("Tenant not found: " + tenantRef));
    }

    public TenantCreationResult createTenant(String name, ApiKeyEnvironment environment,
                                              String email, String webhookUrl) {
        if (tenantRepository.existsByNameIgnoreCase(name)) {
            throw new IllegalStateException("A tenant with the name '" + name + "' already exists");
        }

        Tenant tenant = Tenant.builder()
            .tenantRef(UUID.randomUUID().toString())
            .name(name)
            .keyPrefix(deriveKeyPrefix(name))
            .tenantStatus(TenantStatus.ACTIVE)
            .webhookSecret(UUID.randomUUID().toString())
            .build();
        if (email != null && !email.isBlank()) tenant.setEmail(email);
        if (webhookUrl != null && !webhookUrl.isBlank()) tenant.setWebhookUrl(webhookUrl);

        Tenant saved = tenantRepository.save(tenant);

        ApiKeyService.ApiKeyAndRawKey keyResult =
            apiKeyService.generateAndStore(saved, environment);

        publisher.publishEvent(new TenantCreatedEvent(
            saved.getName(),
            saved.getEmail(),
            environment.name(),
            Instant.now(ClockProvider.getClock())
        ));

        // Pass the saved TenantApiKey entity directly — do NOT use saved.getApiKeys().get(0)
        return new TenantCreationResult(saved, keyResult.entity(), keyResult.rawKey());
    }

    public TenantCreationResult createTenant(String name, ApiKeyEnvironment environment) {
        return createTenant(name, environment, null, null);
    }

    public void updateName(String tenantRef, String name) {
        Tenant tenant = findTenantOrThrow(tenantRef);
        tenant.setName(name);
        tenantRepository.save(tenant);
    }

    public void updateEmail(String tenantRef, String email) {
        Tenant tenant = findTenantOrThrow(tenantRef);
        String oldEmail = tenant.getEmail();
        tenant.setEmail(email);
        tenantRepository.save(tenant);

        publisher.publishEvent(new TenantStatusChangedEvent(
            tenant.getName(),
            oldEmail,
            TenantStatusChangedEvent.EventType.EMAIL_CHANGED,
            Instant.now(ClockProvider.getClock()),
            oldEmail,
            null
        ));
    }

    public void updateWebhookUrl(String tenantRef, String webhookUrl) {
        Tenant tenant = findTenantOrThrow(tenantRef);
        String oldUrl = tenant.getWebhookUrl();
        tenant.setWebhookUrl(webhookUrl);
        tenantRepository.save(tenant);

        publisher.publishEvent(new TenantStatusChangedEvent(
            tenant.getName(),
            tenant.getEmail(),
            TenantStatusChangedEvent.EventType.WEBHOOK_URL_CHANGED,
            Instant.now(ClockProvider.getClock()),
            oldUrl,
            webhookUrl
        ));
    }

    public void suspend(String tenantRef) {
        Tenant tenant = findTenantOrThrow(tenantRef);
        tenant.setTenantStatus(TenantStatus.SUSPENDED);
        tenantRepository.save(tenant);
        keyRepository.revokeAllActiveAndRotatedByTenantId(tenant.getId());

        publisher.publishEvent(new TenantStatusChangedEvent(
            tenant.getName(),
            tenant.getEmail(),
            TenantStatusChangedEvent.EventType.SUSPENDED,
            Instant.now(ClockProvider.getClock()),
            null,
            null
        ));
    }

    public ApiKeyService.ApiKeyAndRawKey reactivate(String tenantRef) {
        Tenant tenant = findTenantOrThrow(tenantRef);
        tenant.setTenantStatus(TenantStatus.ACTIVE);
        tenantRepository.save(tenant);
        ApiKeyService.ApiKeyAndRawKey keyResult = apiKeyService.generateAndStore(tenant, ApiKeyEnvironment.PROD);

        publisher.publishEvent(new TenantStatusChangedEvent(
            tenant.getName(),
            tenant.getEmail(),
            TenantStatusChangedEvent.EventType.REACTIVATED,
            Instant.now(ClockProvider.getClock()),
            null,
            null
        ));

        return keyResult;
    }

    public String regenerateWebhookSecret(String tenantRef) {
        Tenant tenant = findTenantOrThrow(tenantRef);
        String newSecret = UUID.randomUUID().toString();
        tenant.setWebhookSecret(newSecret);
        tenantRepository.save(tenant);

        publisher.publishEvent(new TenantWebhookSecretRegeneratedEvent(
            tenant.getName(),
            tenant.getEmail(),
            Instant.now(ClockProvider.getClock())
        ));

        return newSecret;
    }

    public record TenantCreationResult(Tenant tenant, TenantApiKey key, String rawKey) {}
}
