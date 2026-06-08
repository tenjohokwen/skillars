package com.softropic.skillars.platform.tenant.service;

import com.softropic.skillars.platform.tenant.contract.ApiKeySummaryDto;
import com.softropic.skillars.platform.tenant.contract.TenantDetailDto;
import com.softropic.skillars.platform.tenant.contract.TenantStatus;
import com.softropic.skillars.platform.tenant.contract.TenantSummaryDto;
import com.softropic.skillars.platform.tenant.contract.WebhookSecretDto;
import com.softropic.skillars.platform.tenant.repo.Tenant;
import com.softropic.skillars.platform.tenant.repo.TenantApiKey;
import com.softropic.skillars.platform.tenant.repo.TenantApiKeyRepository;
import com.softropic.skillars.platform.tenant.repo.TenantRepository;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;


@Service
@Transactional(readOnly = true)
public class TenantQueryService {

    private final TenantRepository tenantRepository;
    private final TenantApiKeyRepository keyRepository;

    public TenantQueryService(TenantRepository tenantRepository, TenantApiKeyRepository keyRepository) {
        this.tenantRepository = tenantRepository;
        this.keyRepository = keyRepository;
    }

    public Page<TenantSummaryDto> findAll(TenantStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Tenant> tenants = (status != null)
            ? tenantRepository.findByTenantStatus(status, pageable)
            : tenantRepository.findAll(pageable);
        return tenants.map(t -> new TenantSummaryDto(t.getId(), t.getTenantRef(), t.getName(), t.getTenantStatus(), t.getEmail(), t.getCreatedDate()));
    }

    public TenantDetailDto findByTenantRef(String tenantRef) {
        Tenant tenant = tenantRepository.findByTenantRef(tenantRef)
            .orElseThrow(() -> new EntityNotFoundException("Tenant not found: " + tenantRef));
        List<TenantApiKey> keys = keyRepository.findAllByTenantId(tenant.getId());
        List<ApiKeySummaryDto> keyDtos = keys.stream()
            .map(k -> new ApiKeySummaryDto(k.getId(), k.getKeyPrefix(), k.getEnvironment(), k.getKeyStatus(), k.getCreatedDate()))
            .toList();
        return new TenantDetailDto(
            tenant.getId(), tenant.getTenantRef(), tenant.getName(),
            tenant.getEmail(), tenant.getWebhookUrl(), tenant.getTenantStatus(), keyDtos
        );
    }

    public WebhookSecretDto getWebhookSecret(String tenantRef) {
        Tenant tenant = tenantRepository.findByTenantRef(tenantRef)
            .orElseThrow(() -> new EntityNotFoundException("Tenant not found: " + tenantRef));
        return new WebhookSecretDto(tenant.getWebhookSecret());
    }
}
