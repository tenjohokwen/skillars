package com.softropic.skillars.platform.tenant;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.platform.tenant.contract.ApiKeyEnvironment;
import com.softropic.skillars.platform.tenant.contract.ApiKeyStatus;
import com.softropic.skillars.platform.tenant.repo.TenantApiKey;
import com.softropic.skillars.platform.tenant.repo.TenantApiKeyRepository;
import com.softropic.skillars.platform.tenant.repo.TenantRepository;
import com.softropic.skillars.platform.tenant.service.ApiKeyService;
import com.softropic.skillars.platform.tenant.service.ApiKeyService.ApiKeyAndRawKey;
import com.softropic.skillars.platform.tenant.service.TenantService;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
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

@Disabled
class ApiKeyConcurrentRotationIT extends AbstractIntegrationTest {

    @Autowired private TenantService tenantService;
    @Autowired private ApiKeyService apiKeyService;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private TenantApiKeyRepository tenantApiKeyRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;


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
        int losses = 0;
        for (Future<ApiKeyAndRawKey> f : futures) {
            try {
                ApiKeyAndRawKey result = f.get();
                if (result != null) successes++;
            } catch (ExecutionException ex) {
                if (isExpectedRaceLoss(ex)) {
                    losses++;
                } else {
                    throw ex; // genuinely unexpected — fail loudly
                }
            }
        }

        // 5. Exactly one success, exactly one optimistic-lock loss
        assertThat(successes)
            .as("Exactly one rotate() call must succeed")
            .isEqualTo(1);
        assertThat(losses)
            .as("Exactly one rotate() call must be rejected, by either race-loss mechanism")
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

    /**
     * True when the losing thread lost the race in one of the two legitimate ways.
     *
     * <p>This test previously accepted only {@link org.springframework.orm.ObjectOptimisticLockingFailureException},
     * and failed intermittently with
     * {@code IllegalStateException: Active key already exists for environment: PROD}. That is not
     * a different bug — it is the same race resolving through the other guard:
     *
     * <ul>
     *   <li>the loser's {@code UPDATE} of the old key hits the {@code @Version} check and raises
     *       an optimistic-lock failure; <em>or</em></li>
     *   <li>the loser gets past that and is rejected by the one-ACTIVE-key-per-environment
     *       invariant in {@code ApiKeyService.rotate} ({@code ApiKeyService.java:136}).</li>
     * </ul>
     *
     * <p>Which one fires depends purely on how the two threads interleave, so pinning the test to
     * one of them made it timing-dependent. Both mean exactly one rotation succeeded and the
     * invariant held, which is what {@code concurrentRotation_exactlyOneSucceeds} exists to prove
     * — and the DB-state assertion in step 6 checks that independently of either.
     *
     * <p>This is deliberately NOT a catch-all: any other exception still fails the test.
     */
    private static boolean isExpectedRaceLoss(ExecutionException ex) {
        for (Throwable t = ex.getCause(); t != null; t = t.getCause()) {
            if (t instanceof org.springframework.orm.ObjectOptimisticLockingFailureException) {
                return true;
            }
            if (t instanceof IllegalStateException
                && String.valueOf(t.getMessage()).startsWith("Active key already exists")) {
                return true;
            }
        }
        return false;
    }
}
