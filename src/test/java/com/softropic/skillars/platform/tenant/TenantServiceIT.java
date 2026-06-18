package com.softropic.skillars.platform.tenant;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.platform.tenant.contract.ApiKeyEnvironment;
import com.softropic.skillars.platform.tenant.contract.ApiKeyStatus;
import com.softropic.skillars.platform.tenant.contract.TenantStatus;
import com.softropic.skillars.platform.tenant.repo.TenantApiKey;
import com.softropic.skillars.platform.tenant.repo.TenantApiKeyRepository;
import com.softropic.skillars.platform.tenant.repo.TenantRepository;
import com.softropic.skillars.platform.tenant.service.ApiKeyService;
import com.softropic.skillars.platform.tenant.service.TenantService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"ledger.database.spy=true", "enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
class TenantServiceIT {

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
            jdbcTemplate.execute("DELETE FROM main.tenant_api_key_aud");
            jdbcTemplate.execute("DELETE FROM main.tenant_aud");
            jdbcTemplate.execute("DELETE FROM main.revinfo");
            jdbcTemplate.execute("DELETE FROM main.tenant_api_key");
            jdbcTemplate.execute("DELETE FROM main.tenant");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    // TENT-02
    @Test
    void updateName_persistsChange() {
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Original Name", ApiKeyEnvironment.PROD);
        String ref = result.tenant().getTenantRef();

        tenantService.updateName(ref, "Updated Name");

        assertThat(tenantRepository.findByTenantRef(ref).get().getName())
            .isEqualTo("Updated Name");
    }

    // TENT-03
    @Test
    void updateEmail_persistsChange() {
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Email Test Corp", ApiKeyEnvironment.PROD);
        String ref = result.tenant().getTenantRef();

        tenantService.updateEmail(ref, "test@example.com");

        assertThat(tenantRepository.findByTenantRef(ref).get().getEmail())
            .isEqualTo("test@example.com");
    }

    // TENT-04
    @Test
    void updateWebhookUrl_persistsChange() {
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Webhook URL Corp", ApiKeyEnvironment.PROD);
        String ref = result.tenant().getTenantRef();

        tenantService.updateWebhookUrl(ref, "https://hooks.example.com/callback");

        assertThat(tenantRepository.findByTenantRef(ref).get().getWebhookUrl())
            .isEqualTo("https://hooks.example.com/callback");
    }

    // TENT-07
    @Test
    void suspend_revokesAllKeys() {
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Suspend Test Corp", ApiKeyEnvironment.PROD);
        String ref = result.tenant().getTenantRef();
        Long tenantId = result.tenant().getId();
        Long initialKeyId = result.key().getId();

        // Add a DEV key
        apiKeyService.generateAndStore(result.tenant(), ApiKeyEnvironment.DEV);

        // Rotate the PROD key so there's an ACTIVE + ROTATED
        apiKeyService.rotate(initialKeyId);

        tenantService.suspend(ref);

        assertThat(tenantRepository.findByTenantRef(ref).get().getTenantStatus())
            .isEqualTo(TenantStatus.SUSPENDED);

        List<TenantApiKey> allKeys = tenantApiKeyRepository.findAllByTenantId(tenantId);
        assertThat(allKeys).isNotEmpty();
        assertThat(allKeys)
            .allMatch(k -> k.getKeyStatus() == ApiKeyStatus.REVOKED,
                "all keys must be REVOKED after suspend");
        assertThat(allKeys.stream()
            .noneMatch(k -> k.getKeyStatus() == ApiKeyStatus.ACTIVE
                         || k.getKeyStatus() == ApiKeyStatus.ROTATED))
            .isTrue();
    }

    // TENT-08
    @Test
    void reactivate_generatesNewProdKey() {
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Reactivate Test Corp", ApiKeyEnvironment.PROD);
        String ref = result.tenant().getTenantRef();

        tenantService.suspend(ref);

        ApiKeyService.ApiKeyAndRawKey reactivated = tenantService.reactivate(ref);

        assertThat(tenantRepository.findByTenantRef(ref).get().getTenantStatus())
            .isEqualTo(TenantStatus.ACTIVE);
        assertThat(reactivated.rawKey()).isNotNull().isNotBlank();
        assertThat(reactivated.entity().getKeyStatus()).isEqualTo(ApiKeyStatus.ACTIVE);
        assertThat(reactivated.entity().getEnvironment()).isEqualTo(ApiKeyEnvironment.PROD);
        // Verify the new key can be authenticated
        assertThat(apiKeyService.authenticate(reactivated.rawKey())).isNotNull();
    }

    // AKEY-02
    @Test
    void generateKey_rejectsIfActiveExists() {
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Duplicate Key Corp", ApiKeyEnvironment.PROD);

        assertThatThrownBy(() ->
            apiKeyService.generateAndStore(result.tenant(), ApiKeyEnvironment.PROD))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Active key already exists for environment: PROD");
    }

    // AKEY-08
    @Test
    void rotate_revokesExistingRotatedKeyForSameEnv() {
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Rotate Guard Corp", ApiKeyEnvironment.PROD);
        Long tenantId = result.tenant().getId();
        Long initialKeyId = result.key().getId();

        // First rotation: initial ACTIVE → ROTATED, new ACTIVE created
        ApiKeyService.ApiKeyAndRawKey firstRotated = apiKeyService.rotate(initialKeyId);

        // Second rotation: firstRotated.entity() is now ACTIVE, rotate it
        // The old ROTATED key (initialKeyId) must be REVOKED by AKEY-08
        apiKeyService.rotate(firstRotated.entity().getId());

        List<TenantApiKey> allKeys = tenantApiKeyRepository.findAllByTenantId(tenantId);

        assertThat(allKeys).hasSize(3);
        long activeCount  = allKeys.stream().filter(k -> k.getKeyStatus() == ApiKeyStatus.ACTIVE).count();
        long rotatedCount = allKeys.stream().filter(k -> k.getKeyStatus() == ApiKeyStatus.ROTATED).count();
        long revokedCount = allKeys.stream().filter(k -> k.getKeyStatus() == ApiKeyStatus.REVOKED).count();

        assertThat(activeCount).isEqualTo(1);
        assertThat(rotatedCount).isEqualTo(1);
        assertThat(revokedCount).isEqualTo(1);
    }

    // WSEC-03
    @Test
    void regenerateWebhookSecret_replacesOldValue() {
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Webhook Secret Corp", ApiKeyEnvironment.PROD);
        String ref = result.tenant().getTenantRef();
        String originalSecret = result.tenant().getWebhookSecret();

        String newSecret = tenantService.regenerateWebhookSecret(ref);

        assertThat(newSecret).isNotNull().isNotBlank();
        assertThat(newSecret).isNotEqualTo(originalSecret);
        assertThat(tenantRepository.findByTenantRef(ref).get().getWebhookSecret())
            .isEqualTo(newSecret);
    }

    // WSEC-01 / TENT-01 supplement
    @Test
    void createTenant_setsWebhookSecret() {
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Secret Test Corp", ApiKeyEnvironment.PROD);

        assertThat(result.tenant().getWebhookSecret()).isNotNull();
        assertThat(result.tenant().getWebhookSecret()).matches(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
        );
    }
}
