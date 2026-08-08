package com.softropic.skillars.infrastructure.config;

import com.softropic.skillars.config.AbstractIntegrationTest;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story deferred-4, AC 1: confirms Flyway created main.shedlock and that ShedLock's
 * JdbcTemplateLockProvider is wired up so distributed scheduler locking is active on startup.
 */
class ShedLockConfigIT extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private net.javacrumbs.shedlock.core.LockProvider lockProvider;
    @Autowired private MeterRegistry meterRegistry;

    @Test
    void shedlockTable_existsAfterStartup() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'main' AND table_name = 'shedlock'",
            Integer.class
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    void lockProviderBean_isConfigured() {
        assertThat(lockProvider).isNotNull();
    }

    @Test
    void lockProvider_skipsAndRecordsMetricWhenAlreadyHeld() {
        String lockName = "test-shed-lock-skip-" + UUID.randomUUID();
        LockConfiguration lockConfiguration =
            new LockConfiguration(Instant.now(), lockName, Duration.ofMinutes(1), Duration.ofSeconds(1));

        Optional<SimpleLock> firstAttempt = lockProvider.lock(lockConfiguration);
        assertThat(firstAttempt).as("First caller must acquire the lock").isPresent();

        Optional<SimpleLock> secondAttempt = lockProvider.lock(lockConfiguration);
        assertThat(secondAttempt).as("Second caller must be skipped while the lock is held").isEmpty();

        Counter counter = meterRegistry.find(ShedLockConfig.SCHEDULER_LOCK_SKIPPED)
            .tag("lock_name", lockName)
            .counter();
        assertThat(counter).as("A skip must be recorded via the %s metric", ShedLockConfig.SCHEDULER_LOCK_SKIPPED)
            .isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        firstAttempt.get().unlock();
    }
}
