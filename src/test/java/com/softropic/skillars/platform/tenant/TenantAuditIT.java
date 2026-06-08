package com.softropic.skillars.platform.tenant;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.platform.tenant.contract.ApiKeyEnvironment;
import com.softropic.skillars.platform.tenant.contract.ApiKeyStatus;
import com.softropic.skillars.platform.tenant.repo.TenantApiKey;
import com.softropic.skillars.platform.tenant.repo.TenantApiKeyRepository;
import com.softropic.skillars.platform.tenant.repo.TenantRepository;
import com.softropic.skillars.platform.tenant.service.ApiKeyService;
import com.softropic.skillars.platform.tenant.service.TenantService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
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
class TenantAuditIT {

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

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("admin@test.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM main.tenant_api_key_aud");
            jdbcTemplate.execute("DELETE FROM main.tenant_aud");
            jdbcTemplate.execute("DELETE FROM main.revinfo");
            jdbcTemplate.execute("DELETE FROM main.tenant_api_key");
            jdbcTemplate.execute("DELETE FROM main.tenant");
            return null;
        });
        SecurityContextHolder.clearContext();
    }

    // AUDIT-01
    @Test
    void updateName_createsAuditRow() {
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Audit Original", ApiKeyEnvironment.PROD);
        Long tenantId = result.tenant().getId();
        String ref = result.tenant().getTenantRef();

        tenantService.updateName(ref, "Audit Updated");

        // Assert update audit row exists with the new name
        Integer updateCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.tenant_aud WHERE id = ? AND name = ?",
            Integer.class, tenantId, "Audit Updated");
        assertThat(updateCount).isGreaterThanOrEqualTo(1);

        // Assert creation row exists (revtype = 0 means INSERT)
        Integer createCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.tenant_aud WHERE id = ? AND revtype = 0",
            Integer.class, tenantId);
        assertThat(createCount).isGreaterThanOrEqualTo(1);
    }

    // AUDIT-02
    @Test
    void rotate_createsAuditRow() {
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Audit Rotate Corp", ApiKeyEnvironment.PROD);
        Long tenantId = result.tenant().getId();
        Long initialKeyId = result.key().getId();

        ApiKeyService.ApiKeyAndRawKey rotated = apiKeyService.rotate(initialKeyId);

        // The old key should have a ROTATED audit entry
        Integer oldKeyRotatedCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.tenant_api_key_aud WHERE id = ? AND key_status = 'ROTATED'",
            Integer.class, initialKeyId);
        assertThat(oldKeyRotatedCount).isGreaterThanOrEqualTo(1);

        // The new ACTIVE key should have an INSERT audit entry (revtype = 0)
        Long newKeyId = rotated.entity().getId();
        Integer newKeyInsertCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.tenant_api_key_aud WHERE id = ? AND key_status = 'ACTIVE' AND revtype = 0",
            Integer.class, newKeyId);
        assertThat(newKeyInsertCount).isGreaterThanOrEqualTo(1);
    }

    // AUDIT-03
    @Test
    void generateKey_auditCapturesAdminIdentity() {
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Admin Identity Corp", ApiKeyEnvironment.PROD);
        Long tenantId = result.tenant().getId();
        Long keyId = result.key().getId();

        // The tenant INSERT audit row should capture the admin identity
        String tenantCreatedBy = jdbcTemplate.queryForObject(
            "SELECT created_by FROM main.tenant_aud WHERE id = ? AND revtype = 0",
            String.class, tenantId);
        assertThat(tenantCreatedBy).isEqualTo("admin@test.com");

        // The key INSERT audit row should also capture the admin identity
        String keyCreatedBy = jdbcTemplate.queryForObject(
            "SELECT created_by FROM main.tenant_api_key_aud WHERE id = ? AND revtype = 0",
            String.class, keyId);
        assertThat(keyCreatedBy).isEqualTo("admin@test.com");
    }
}
