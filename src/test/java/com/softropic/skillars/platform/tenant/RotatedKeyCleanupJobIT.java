package com.softropic.skillars.platform.tenant;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.platform.tenant.contract.ApiKeyEnvironment;
import com.softropic.skillars.platform.tenant.contract.ApiKeyStatus;
import com.softropic.skillars.platform.tenant.repo.TenantApiKey;
import com.softropic.skillars.platform.tenant.repo.TenantApiKeyRepository;
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


@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"ledger.database.spy=true", "enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
class RotatedKeyCleanupJobIT {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private ApiKeyService apiKeyService;

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

    // AKEY-05: overdue ROTATED key is automatically revoked
    @Test
    void revokeExpiredRotatedKeys_revokesOverdueKey() {
        TenantApiKey rotatedKey = createAndRotateKey();
        Long rotatedKeyId = rotatedKey.getId();

        // Backdate rotated_at to 25 hours ago (past the 24h grace period)
        // Use SQL interval to avoid JVM timezone vs Postgres TIMESTAMP comparison issues
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "UPDATE main.tenant_api_key SET rotated_at = NOW() - INTERVAL '25 hours' WHERE id = ?",
                rotatedKeyId);
            return null;
        });

        int revokedCount = apiKeyService.revokeExpiredRotatedKeys();

        assertThat(revokedCount).isEqualTo(1);
        TenantApiKey updated = tenantApiKeyRepository.findById(rotatedKeyId).orElseThrow();
        assertThat(updated.getKeyStatus()).isEqualTo(ApiKeyStatus.REVOKED);
    }

    // AKEY-05: key still in grace period is left untouched
    @Test
    void revokeExpiredRotatedKeys_leavesUnderGraceKeyUntouched() {
        TenantApiKey rotatedKey = createAndRotateKey();
        Long rotatedKeyId = rotatedKey.getId();

        // Set rotated_at to 1 hour ago (well within 24h grace period)
        // Use SQL interval to avoid JVM timezone vs Postgres TIMESTAMP comparison issues
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "UPDATE main.tenant_api_key SET rotated_at = NOW() - INTERVAL '1 hour' WHERE id = ?",
                rotatedKeyId);
            return null;
        });

        int revokedCount = apiKeyService.revokeExpiredRotatedKeys();

        assertThat(revokedCount).isEqualTo(0);
        TenantApiKey unchanged = tenantApiKeyRepository.findById(rotatedKeyId).orElseThrow();
        assertThat(unchanged.getKeyStatus()).isEqualTo(ApiKeyStatus.ROTATED);
    }

    // AKEY-05: no-op when no ROTATED keys exist
    @Test
    void revokeExpiredRotatedKeys_isIdempotent_noOp() {
        // Create tenant but do NOT rotate any key (only ACTIVE key exists)
        tenantService.createTenant("Idempotent Test Corp", ApiKeyEnvironment.PROD);

        int revokedCount = apiKeyService.revokeExpiredRotatedKeys();

        assertThat(revokedCount).isEqualTo(0);

        // Verify all existing keys are still ACTIVE
        // (query all keys to confirm no unexpected state changes)
        List<TenantApiKey> allKeys = tenantApiKeyRepository.findAll();
        assertThat(allKeys).isNotEmpty();
        assertThat(allKeys).allMatch(k -> k.getKeyStatus() == ApiKeyStatus.ACTIVE);
    }

    // AKEY-05: Envers audit row created for auto-revocation
    @Test
    void revokeExpiredRotatedKeys_createsEnversAuditRow() {
        TenantApiKey rotatedKey = createAndRotateKey();
        Long rotatedKeyId = rotatedKey.getId();

        // Backdate rotated_at to 25 hours ago
        // Use SQL interval to avoid JVM timezone vs Postgres TIMESTAMP comparison issues
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "UPDATE main.tenant_api_key SET rotated_at = NOW() - INTERVAL '25 hours' WHERE id = ?",
                rotatedKeyId);
            return null;
        });

        apiKeyService.revokeExpiredRotatedKeys();

        Integer revokedAuditCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.tenant_api_key_aud WHERE id = ? AND key_status = 'REVOKED'",
            Integer.class, rotatedKeyId);
        assertThat(revokedAuditCount).isGreaterThanOrEqualTo(1);
    }

    // Helper: create a tenant and rotate the initial ACTIVE key; returns the ROTATED key
    private TenantApiKey createAndRotateKey() {
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Cleanup Corp", ApiKeyEnvironment.PROD);
        Long keyId = tenantApiKeyRepository
            .findAllByTenantId(result.tenant().getId())
            .get(0).getId();
        apiKeyService.rotate(keyId);
        // After rotation: original key is ROTATED, new key is ACTIVE
        return tenantApiKeyRepository.findAllByTenantId(result.tenant().getId())
            .stream()
            .filter(k -> k.getKeyStatus() == ApiKeyStatus.ROTATED)
            .findFirst()
            .orElseThrow();
    }
}
