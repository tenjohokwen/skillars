package com.softropic.skillars.infrastructure.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Optional;

@Slf4j
@Configuration
public class ShedLockConfig {

    public static final String SCHEDULER_LOCK_SKIPPED = "scheduler.lock.skipped";

    @Bean
    public LockProvider lockProvider(DataSource dataSource, MeterRegistry meterRegistry) {
        LockProvider delegate = new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName("main.shedlock")
                .usingDbTime()
                .build()
        );

        // ShedLock's lock() returns Optional.empty() precisely when another instance already
        // holds the lock — the only signal a @SchedulerLock-annotated run was skipped. Without
        // this, a skipped run is silent and indistinguishable from a job that failed to fire.
        return lockConfiguration -> {
            Optional<net.javacrumbs.shedlock.core.SimpleLock> lock = delegate.lock(lockConfiguration);
            if (lock.isEmpty()) {
                log.info("Scheduler lock '{}' is held by another instance — skipping this run",
                    lockConfiguration.getName());
                Counter.builder(SCHEDULER_LOCK_SKIPPED)
                    .tag("lock_name", lockConfiguration.getName())
                    .description("Runs skipped because another instance already held the ShedLock")
                    .register(meterRegistry)
                    .increment();
            }
            return lock;
        };
    }
}
