package com.softropic.skillars.platform.tenant;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.platform.tenant.contract.ApiKeyEnvironment;
import com.softropic.skillars.platform.tenant.contract.ApiKeyStatus;
import com.softropic.skillars.platform.tenant.repo.TenantApiKey;
import com.softropic.skillars.platform.tenant.repo.TenantApiKeyRepository;
import com.softropic.skillars.platform.tenant.repo.TenantRepository;
import com.softropic.skillars.platform.tenant.service.ApiKeyService;
import com.softropic.skillars.platform.tenant.service.ApiKeyService.ApiKeyAndRawKey;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"ledger.database.spy=true", "enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
class ApiKeyConcurrentRotationIT {

    @Autowired private TenantService tenantService;
    @Autowired private ApiKeyService apiKeyService;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private TenantApiKeyRepository tenantApiKeyRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

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
    }

    @Test
    void concurrentRotation_exactlyOneSucceeds() throws Exception {
        // 1. Seed a tenant — TenantService.createTenant provisions the initial PROD ACTIVE key.
        TenantService.TenantCreationResult tenantResult =
            tenantService.createTenant("AKEY-09-Tenant", ApiKeyEnvironment.PROD);
        Long tenantId = tenantResult.tenant().getId();

        // 2. Resolve the ACTIVE key id
        TenantApiKey activeKey = tenantApiKeyRepository
            .findActiveKeyByTenantIdAndEnvironment(tenantId, ApiKeyEnvironment.PROD)
            .orElseThrow(() -> new AssertionError("Seed tenant must have an ACTIVE PROD key"));
        Long keyId = activeKey.getId();

        // 3. Two threads behind a CyclicBarrier — both call rotate(keyId) simultaneously
        int THREADS = 2;
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        List<Future<ApiKeyAndRawKey>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                try { barrier.await(10, TimeUnit.SECONDS); } catch (Exception ignored) {}
                return apiKeyService.rotate(keyId);
            }));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS))
            .as("Both rotation threads must complete within 60 seconds")
            .isTrue();

        // 4. Collect outcomes
        int successes = 0;
        int optimisticLockLosses = 0;
        for (Future<ApiKeyAndRawKey> f : futures) {
            try {
                ApiKeyAndRawKey result = f.get();
                if (result != null) successes++;
            } catch (ExecutionException ex) {
                Throwable root = ex.getCause();
                // Hibernate may wrap multiple layers — unwrap until we find the optimistic lock type
                while (root != null
                    && !(root instanceof org.springframework.orm.ObjectOptimisticLockingFailureException)) {
                    root = root.getCause();
                }
                if (root instanceof org.springframework.orm.ObjectOptimisticLockingFailureException) {
                    optimisticLockLosses++;
                } else {
                    throw ex; // unexpected exception — fail loudly
                }
            }
        }

        // 5. Exactly one success, exactly one optimistic-lock loss
        assertThat(successes)
            .as("Exactly one rotate() call must succeed")
            .isEqualTo(1);
        assertThat(optimisticLockLosses)
            .as("Exactly one rotate() call must raise ObjectOptimisticLockingFailureException")
            .isEqualTo(1);

        // 6. DB state: exactly one ACTIVE key for the tenant's PROD environment
        List<TenantApiKey> allKeysForTenant = tenantApiKeyRepository.findAllByTenantId(tenantId);
        long activeCount = allKeysForTenant.stream()
            .filter(k -> k.getEnvironment() == ApiKeyEnvironment.PROD)
            .filter(k -> k.getKeyStatus() == ApiKeyStatus.ACTIVE)
            .count();
        assertThat(activeCount)
            .as("Exactly one ACTIVE PROD key must exist after the race")
            .isEqualTo(1);
    }
}
