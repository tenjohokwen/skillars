package com.softropic.skillars.platform.tenant.service;

import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.tenant.contract.ApiKeyEnvironment;
import com.softropic.skillars.platform.tenant.contract.ApiKeyStatus;
import com.softropic.skillars.platform.tenant.contract.event.TenantApiKeyEvent;
import com.softropic.skillars.platform.tenant.repo.Tenant;
import com.softropic.skillars.platform.tenant.repo.TenantApiKey;
import com.softropic.skillars.platform.tenant.repo.TenantApiKeyRepository;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityNotFoundException;


@Service
@Transactional
public class ApiKeyService {

    private static final Duration GRACE_PERIOD = Duration.ofHours(24);

    private final TenantApiKeyRepository keyRepository;
    private final ApplicationEventPublisher publisher;

    public ApiKeyService(TenantApiKeyRepository keyRepository, ApplicationEventPublisher publisher) {
        this.keyRepository = keyRepository;
        this.publisher = publisher;
    }

    public ApiKeyAndRawKey generateAndStore(Tenant tenant, ApiKeyEnvironment environment) {
        // AKEY-02: Reject if an ACTIVE key already exists for this tenant+environment
        keyRepository.findActiveKeyByTenantIdAndEnvironment(tenant.getId(), environment)
            .ifPresent(existing -> {
                throw new IllegalStateException(
                    "Active key already exists for environment: " + environment);
            });
        String prefix = tenant.getKeyPrefix();
        String rawKey = generateSecureKey(prefix);
        String hash   = DigestUtils.sha256Hex(rawKey);

        TenantApiKey entity = TenantApiKey.builder()
            .tenant(tenant)
            .keyHash(hash)
            .keyPrefix(prefix)
            .keyStatus(ApiKeyStatus.ACTIVE)
            .environment(environment)
            .build();

        TenantApiKey saved = keyRepository.save(entity);
        return new ApiKeyAndRawKey(saved, rawKey);
    }

    @Transactional(readOnly = true)
    public TenantApiKey authenticate(String rawKey) {
        String hash = DigestUtils.sha256Hex(rawKey);
        Instant graceDeadline = Instant.now().minus(GRACE_PERIOD);
        return keyRepository.findValidKeyByHash(hash, graceDeadline)
            .orElseThrow(() -> new BadCredentialsException("Invalid or expired API key"));
    }

    public ApiKeyAndRawKey rotate(Long keyId) {
        TenantApiKey old = keyRepository.findById(keyId)
            .orElseThrow(() -> new EntityNotFoundException("Key not found: " + keyId));
        // AKEY-08: Revoke any existing ROTATED key for the same (tenant, environment)
        // to prevent overlapping grace periods
        keyRepository.findRotatedKeyByTenantIdAndEnvironment(
                old.getTenant().getId(), old.getEnvironment())
            .ifPresent(previousRotated -> {
                previousRotated.setKeyStatus(ApiKeyStatus.REVOKED);
                keyRepository.saveAndFlush(previousRotated);
            });
        old.setKeyStatus(ApiKeyStatus.ROTATED);
        old.setRotatedAt(Instant.now());
        keyRepository.saveAndFlush(old);

        ApiKeyAndRawKey newKeyResult = generateAndStore(old.getTenant(), old.getEnvironment());

        publisher.publishEvent(new TenantApiKeyEvent(
            old.getTenant().getName(),
            old.getTenant().getEmail(),
            newKeyResult.entity().getKeyPrefix(),
            old.getEnvironment().name(),
            TenantApiKeyEvent.Action.ROTATED,
            Instant.now(ClockProvider.getClock())
        ));

        return newKeyResult;
    }

    public int revokeExpiredRotatedKeys() {
        Instant cutoff = Instant.now().minus(GRACE_PERIOD);
        List<TenantApiKey> expired = keyRepository.findExpiredRotatedKeys(cutoff);
        for (TenantApiKey key : expired) {
            key.setKeyStatus(ApiKeyStatus.REVOKED);
            keyRepository.save(key);
        }
        return expired.size();
    }

    public void revoke(Long keyId) {
        TenantApiKey key = keyRepository.findById(keyId)
            .orElseThrow(() -> new EntityNotFoundException("Key not found: " + keyId));
        key.setKeyStatus(ApiKeyStatus.REVOKED);
        keyRepository.save(key);

        publisher.publishEvent(new TenantApiKeyEvent(
            key.getTenant().getName(),
            key.getTenant().getEmail(),
            key.getKeyPrefix(),
            key.getEnvironment().name(),
            TenantApiKeyEvent.Action.REVOKED,
            Instant.now(ClockProvider.getClock())
        ));
    }

    // NOTIF-04: reactivate a revoked API key
    public void reactivate(Long keyId) {
        TenantApiKey key = keyRepository.findById(keyId)
            .orElseThrow(() -> new EntityNotFoundException("Key not found: " + keyId));
        if (key.getKeyStatus() != ApiKeyStatus.REVOKED) {
            throw new IllegalStateException("Only revoked keys can be reactivated");
        }
        // AKEY-02 safety: reject if an ACTIVE key already exists for this tenant+environment
        keyRepository.findActiveKeyByTenantIdAndEnvironment(key.getTenant().getId(), key.getEnvironment())
            .ifPresent(existing -> {
                throw new IllegalStateException(
                    "Active key already exists for environment: " + key.getEnvironment());
            });
        key.setKeyStatus(ApiKeyStatus.ACTIVE);
        keyRepository.save(key);

        publisher.publishEvent(new TenantApiKeyEvent(
            key.getTenant().getName(),
            key.getTenant().getEmail(),
            key.getKeyPrefix(),
            key.getEnvironment().name(),
            TenantApiKeyEvent.Action.REACTIVATED,
            Instant.now(ClockProvider.getClock())
        ));
    }

    private String generateSecureKey(String keyPrefix) {
        return keyPrefix + "_" + UUID.randomUUID().toString();
    }

    public record ApiKeyAndRawKey(TenantApiKey entity, String rawKey) {}
}
