package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.BaseVideoIT;
import com.softropic.skillars.platform.video.contract.exception.QuotaExceededException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class QuotaServiceConcurrencyTest extends BaseVideoIT {

    @Autowired QuotaService quotaService;

    @MockitoBean QuotaConfigService quotaConfigService;

    private String testOwnerId;

    @BeforeEach
    void setUp() {
        testOwnerId = "quota-test-coach-" + UUID.randomUUID();
        when(quotaConfigService.getStorageQuotaBytes(anyString())).thenReturn(100L);
        when(quotaConfigService.getReservationTimeoutMinutes()).thenReturn(60L);
        transactionTemplate.execute(status -> {
        jdbcTemplate.update(
            "INSERT INTO main.video_quotas (user_id, storage_used_bytes, bandwidth_used_bytes, bandwidth_period_start) VALUES (?, 0, 0, NOW())",
            testOwnerId
        );
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM main.video_quota_reservations WHERE user_id = ?", testOwnerId);
            jdbcTemplate.update("DELETE FROM main.video_quotas WHERE user_id = ?", testOwnerId);
            return null;
        });
    }

    @Test
    void concurrentReserve_onlyOneSucceeds() throws Exception {
        // Two threads attempt to reserve 100 bytes when quota has exactly 100 bytes remaining
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger exceedCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicReference<Throwable> unexpectedException = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 2; i++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    quotaService.reserve(testOwnerId, 100L);
                    successCount.incrementAndGet();
                } catch (QuotaExceededException e) {
                    exceedCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    unexpectedException.set(t);
                }
            }));
        }

        startLatch.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        executor.shutdown();

        if (unexpectedException.get() != null) {
            throw new AssertionError("Unexpected exception in concurrent thread", unexpectedException.get());
        }

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(exceedCount.get()).isEqualTo(1);

        Integer activeCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.video_quota_reservations WHERE user_id = ? AND status = 'ACTIVE'",
            Integer.class, testOwnerId);
        assertThat(activeCount).isEqualTo(1);
    }

    @Test
    void reserve_scoutTierThrowsQuotaExceeded() {
        when(quotaConfigService.getStorageQuotaBytes(anyString())).thenReturn(0L);

        org.junit.jupiter.api.Assertions.assertThrows(QuotaExceededException.class,
            () -> quotaService.reserve(testOwnerId, 100L));
    }

    @Test
    void commit_incrementsStorageAtomically() {
        String handle = quotaService.reserve(testOwnerId, 50L);
        quotaService.commit(handle);

        Long storageUsed = jdbcTemplate.queryForObject(
            "SELECT storage_used_bytes FROM main.video_quotas WHERE user_id = ?",
            Long.class, testOwnerId);
        assertThat(storageUsed).isEqualTo(50L);

        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM main.video_quota_reservations WHERE id = ?::uuid",
            String.class, handle);
        assertThat(status).isEqualTo("COMMITTED");
    }

    @Test
    void release_doesNotDecrementStorage() {
        String handle = quotaService.reserve(testOwnerId, 50L);
        quotaService.release(handle);

        Long storageUsed = jdbcTemplate.queryForObject(
            "SELECT storage_used_bytes FROM main.video_quotas WHERE user_id = ?",
            Long.class, testOwnerId);
        assertThat(storageUsed).isEqualTo(0L);

        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM main.video_quota_reservations WHERE id = ?::uuid",
            String.class, handle);
        assertThat(status).isEqualTo("RELEASED");
    }

    @Test
    void commit_isIdempotent() {
        String handle = quotaService.reserve(testOwnerId, 50L);
        quotaService.commit(handle);
        quotaService.commit(handle);  // second call is a no-op

        Long storageUsed = jdbcTemplate.queryForObject(
            "SELECT storage_used_bytes FROM main.video_quotas WHERE user_id = ?",
            Long.class, testOwnerId);
        assertThat(storageUsed).isEqualTo(50L);  // incremented only once
    }
}
