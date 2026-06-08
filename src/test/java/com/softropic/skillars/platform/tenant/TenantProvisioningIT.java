package com.softropic.skillars.platform.tenant;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.platform.tenant.contract.ApiKeyStatus;
import com.softropic.skillars.platform.tenant.contract.TenantStatus;
import com.softropic.skillars.platform.tenant.repo.TenantApiKey;
import com.softropic.skillars.platform.tenant.repo.TenantApiKeyRepository;
import com.softropic.skillars.platform.tenant.repo.TenantRepository;
import com.softropic.skillars.platform.tenant.service.ApiKeyService;
import com.softropic.skillars.platform.tenant.contract.ApiKeyEnvironment;
import com.softropic.skillars.platform.tenant.service.TenantService;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"ledger.database.spy=true", "enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
class TenantProvisioningIT {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private TenantApiKeyRepository tenantApiKeyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("delete from main.tenant_api_key");
            jdbcTemplate.execute("delete from main.tenant");
            return null;
        });
    }

    @Test
    void createTenant_persistsEntities() {
        TenantService.TenantCreationResult result = tenantService.createTenant("Acme Corp", ApiKeyEnvironment.PROD);

        // Tenant assertions
        assertThat(result.tenant()).isNotNull();
        assertThat(result.tenant().getId()).isNotNull();
        assertThat(result.tenant().getTenantRef()).isNotBlank();
        assertThat(result.tenant().getTenantRef()).matches(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
        );
        assertThat(result.tenant().getName()).isEqualTo("Acme Corp");
        assertThat(result.tenant().getTenantStatus()).isEqualTo(TenantStatus.ACTIVE);

        // WebhookSecret assertion (WSEC-01)
        assertThat(result.tenant().getWebhookSecret()).isNotNull();
        assertThat(result.tenant().getWebhookSecret()).matches(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
        );

        // Key assertions
        TenantApiKey key = result.key();
        assertThat(key).isNotNull();
        assertThat(key.getId()).isNotNull();
        assertThat(key.getKeyStatus()).isEqualTo(ApiKeyStatus.ACTIVE);
        assertThat(key.getRotatedAt()).isNull();

        // Hash verification — raw key must NOT be stored, only the hash
        String rawKey = result.rawKey();
        assertThat(rawKey).isNotBlank();
        assertThat(key.getKeyHash()).isNotEqualTo(rawKey);
        assertThat(key.getKeyHash()).isEqualTo(DigestUtils.sha256Hex(rawKey));

        // AKEY-01: raw key follows PREFIX_UUID format
        assertThat(rawKey).matches("^[A-Z0-9]{2,3}_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        assertThat(rawKey).startsWith("ACM_");

        // Prefix check — derived from tenant name (v5: keyPrefix is 3-char uppercase from name)
        assertThat(key.getKeyPrefix()).isEqualTo("ACM");

        // Verify row exists in DB
        assertThat(tenantRepository.findByTenantRef(result.tenant().getTenantRef())).isPresent();
        List<TenantApiKey> keys = tenantApiKeyRepository.findAllByTenantId(result.tenant().getId());
        assertThat(keys).hasSize(1);
    }

    @Test
    void authenticate_validKey_succeeds() {
        TenantService.TenantCreationResult result = tenantService.createTenant("Auth Test Corp", ApiKeyEnvironment.PROD);
        String rawKey = result.rawKey();

        TenantApiKey authenticated = apiKeyService.authenticate(rawKey);

        assertThat(authenticated).isNotNull();
        assertThat(authenticated.getTenant().getTenantRef()).isEqualTo(result.tenant().getTenantRef());
    }

    @Test
    void authenticate_revokedKey_throws() {
        TenantService.TenantCreationResult result = tenantService.createTenant("Revoke Test Corp", ApiKeyEnvironment.PROD);
        String rawKey = result.rawKey();
        Long keyId = result.key().getId();

        apiKeyService.revoke(keyId);

        assertThatThrownBy(() -> apiKeyService.authenticate(rawKey))
            .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void rotate_oldKeyValidDuringGrace() {
        TenantService.TenantCreationResult result = tenantService.createTenant("Rotate Test Corp", ApiKeyEnvironment.PROD);
        String oldRawKey = result.rawKey();
        Long oldKeyId = result.key().getId();

        ApiKeyService.ApiKeyAndRawKey rotated = apiKeyService.rotate(oldKeyId);
        String newRawKey = rotated.rawKey();

        // Both old and new keys must be accepted during grace period
        TenantApiKey oldAuthenticated = apiKeyService.authenticate(oldRawKey);
        assertThat(oldAuthenticated.getKeyStatus()).isEqualTo(ApiKeyStatus.ROTATED);
        assertThat(oldAuthenticated.getRotatedAt()).isNotNull();

        TenantApiKey newAuthenticated = apiKeyService.authenticate(newRawKey);
        assertThat(newAuthenticated.getKeyStatus()).isEqualTo(ApiKeyStatus.ACTIVE);
    }

    @Test
    void authenticate_rotatedKeyExpired_throws() {
        TenantService.TenantCreationResult result = tenantService.createTenant("Expired Rotation Corp", ApiKeyEnvironment.PROD);
        String oldRawKey = result.rawKey();
        Long oldKeyId = result.key().getId();

        ApiKeyService.ApiKeyAndRawKey rotated = apiKeyService.rotate(oldKeyId);
        String newRawKey = rotated.rawKey();

        // Simulate expiry by setting rotated_at to 25 hours ago via JPA
        // (avoids JDBC Timestamp timezone ambiguity; Hibernate sends Instant as UTC)
        transactionTemplate.execute(status -> {
            TenantApiKey expiredKey = tenantApiKeyRepository.findById(oldKeyId).orElseThrow();
            expiredKey.setRotatedAt(Instant.now().minus(Duration.ofHours(25)));
            tenantApiKeyRepository.save(expiredKey);
            return null;
        });

        // Old key must be rejected after grace period
        assertThatThrownBy(() -> apiKeyService.authenticate(oldRawKey))
            .isInstanceOf(BadCredentialsException.class);

        // New key must still be accepted
        TenantApiKey newAuthenticated = apiKeyService.authenticate(newRawKey);
        assertThat(newAuthenticated.getKeyStatus()).isEqualTo(ApiKeyStatus.ACTIVE);
    }

    @Test
    void tenantIsolation_cannotSeeOtherTenant() {
        TenantService.TenantCreationResult tenant1 = tenantService.createTenant("Tenant One", ApiKeyEnvironment.PROD);
        TenantService.TenantCreationResult tenant2 = tenantService.createTenant("Tenant Two", ApiKeyEnvironment.PROD);

        List<TenantApiKey> tenant1Keys = tenantApiKeyRepository.findAllByTenantId(tenant1.tenant().getId());
        List<TenantApiKey> tenant2Keys = tenantApiKeyRepository.findAllByTenantId(tenant2.tenant().getId());

        // Tenant 1's keys must not contain Tenant 2's key id
        assertThat(tenant1Keys).hasSize(1);
        assertThat(tenant2Keys).hasSize(1);
        assertThat(tenant1Keys.get(0).getId()).isNotEqualTo(tenant2Keys.get(0).getId());

        // Tenant 1's keys must not include keys belonging to tenant 2
        Long tenant2KeyId = tenant2Keys.get(0).getId();
        assertThat(tenant1Keys.stream().noneMatch(k -> k.getId().equals(tenant2KeyId))).isTrue();
    }
}
