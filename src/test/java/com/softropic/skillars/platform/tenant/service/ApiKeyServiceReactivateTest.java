package com.softropic.skillars.platform.tenant.service;

import com.softropic.skillars.platform.tenant.contract.ApiKeyEnvironment;
import com.softropic.skillars.platform.tenant.contract.ApiKeyStatus;
import com.softropic.skillars.platform.tenant.contract.event.TenantApiKeyEvent;
import com.softropic.skillars.platform.tenant.repo.Tenant;
import com.softropic.skillars.platform.tenant.repo.TenantApiKey;
import com.softropic.skillars.platform.tenant.repo.TenantApiKeyRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import jakarta.persistence.EntityNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ApiKeyService.reactivate(Long keyId).
 *
 * <p>Tests cover: happy path (REVOKED -> ACTIVE + event published), not-found,
 * status guard for ACTIVE keys, status guard for ROTATED keys, and the AKEY-02
 * safety check (active key already exists for same tenant+environment).
 */
@ExtendWith(MockitoExtension.class)
class ApiKeyServiceReactivateTest {

    @Mock
    private TenantApiKeyRepository keyRepository;

    @Mock
    private ApplicationEventPublisher publisher;

    @Captor
    private ArgumentCaptor<TenantApiKeyEvent> eventCaptor;

    private ApiKeyService apiKeyService;

    private static final Long KEY_ID = 1L;
    private static final Long TENANT_ID = 100L;
    private static final String TENANT_NAME = "Test Corp";
    private static final String TENANT_EMAIL = "test@corp.com";
    private static final String KEY_PREFIX = "prod_abc123";
    private static final ApiKeyEnvironment ENVIRONMENT = ApiKeyEnvironment.PROD;

    @BeforeEach
    void setUp() {
        apiKeyService = new ApiKeyService(keyRepository, publisher);
    }

    // -------------------------------------------------------------------------
    // Helper builders
    // -------------------------------------------------------------------------

    private Tenant buildTenant() {
        Tenant tenant = new Tenant();
        tenant.setId(TENANT_ID);
        tenant.setName(TENANT_NAME);
        tenant.setEmail(TENANT_EMAIL);
        return tenant;
    }

    private TenantApiKey buildKey(ApiKeyStatus status) {
        Tenant tenant = buildTenant();
        TenantApiKey key = new TenantApiKey();
        key.setId(KEY_ID);
        key.setTenant(tenant);
        key.setKeyPrefix(KEY_PREFIX);
        key.setKeyStatus(status);
        key.setEnvironment(ENVIRONMENT);
        return key;
    }

    // -------------------------------------------------------------------------
    // Test 1: Happy path — REVOKED key is reactivated successfully
    // -------------------------------------------------------------------------

    @Test
    void reactivate_revokedKey_setsStatusActiveAndPublishesEvent() {
        TenantApiKey revokedKey = buildKey(ApiKeyStatus.REVOKED);
        when(keyRepository.findById(KEY_ID)).thenReturn(Optional.of(revokedKey));
        when(keyRepository.findActiveKeyByTenantIdAndEnvironment(TENANT_ID, ENVIRONMENT))
            .thenReturn(Optional.empty());
        when(keyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        apiKeyService.reactivate(KEY_ID);

        // Key status must be updated to ACTIVE
        assertThat(revokedKey.getKeyStatus()).isEqualTo(ApiKeyStatus.ACTIVE);

        // Event must be published with correct fields
        verify(publisher).publishEvent(eventCaptor.capture());
        TenantApiKeyEvent event = eventCaptor.getValue();
        assertThat(event.action()).isEqualTo(TenantApiKeyEvent.Action.REACTIVATED);
        assertThat(event.tenantName()).isEqualTo(TENANT_NAME);
        assertThat(event.tenantEmail()).isEqualTo(TENANT_EMAIL);
        assertThat(event.keyPrefix()).isEqualTo(KEY_PREFIX);
        assertThat(event.environment()).isEqualTo(ENVIRONMENT.name());
        assertThat(event.occurredAt()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Test 2: Not found — EntityNotFoundException when key does not exist
    // -------------------------------------------------------------------------

    @Test
    void reactivate_nonExistentKey_throwsEntityNotFoundException() {
        when(keyRepository.findById(KEY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> apiKeyService.reactivate(KEY_ID))
            .isInstanceOf(EntityNotFoundException.class)
            .hasMessageContaining(String.valueOf(KEY_ID));

        verify(publisher, never()).publishEvent(any());
    }

    // -------------------------------------------------------------------------
    // Test 3: Status guard — ACTIVE key cannot be reactivated
    // -------------------------------------------------------------------------

    @Test
    void reactivate_activeKey_throwsIllegalStateException() {
        TenantApiKey activeKey = buildKey(ApiKeyStatus.ACTIVE);
        when(keyRepository.findById(KEY_ID)).thenReturn(Optional.of(activeKey));

        assertThatThrownBy(() -> apiKeyService.reactivate(KEY_ID))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Only revoked keys can be reactivated");

        verify(publisher, never()).publishEvent(any());
    }

    // -------------------------------------------------------------------------
    // Test 4: Status guard — ROTATED key cannot be reactivated
    // -------------------------------------------------------------------------

    @Test
    void reactivate_rotatedKey_throwsIllegalStateException() {
        TenantApiKey rotatedKey = buildKey(ApiKeyStatus.ROTATED);
        when(keyRepository.findById(KEY_ID)).thenReturn(Optional.of(rotatedKey));

        assertThatThrownBy(() -> apiKeyService.reactivate(KEY_ID))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Only revoked keys can be reactivated");

        verify(publisher, never()).publishEvent(any());
    }

    // -------------------------------------------------------------------------
    // Test 5: AKEY-02 safety — active key already exists for same environment
    // -------------------------------------------------------------------------

    @Test
    void reactivate_revokedKey_whenActiveKeyAlreadyExists_throwsIllegalStateException() {
        TenantApiKey revokedKey = buildKey(ApiKeyStatus.REVOKED);
        TenantApiKey existingActive = buildKey(ApiKeyStatus.ACTIVE);
        existingActive.setId(999L); // different key

        when(keyRepository.findById(KEY_ID)).thenReturn(Optional.of(revokedKey));
        when(keyRepository.findActiveKeyByTenantIdAndEnvironment(TENANT_ID, ENVIRONMENT))
            .thenReturn(Optional.of(existingActive));

        assertThatThrownBy(() -> apiKeyService.reactivate(KEY_ID))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Active key already exists");

        verify(publisher, never()).publishEvent(any());
    }
}
