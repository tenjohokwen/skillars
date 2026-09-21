package com.softropic.skillars.infrastructure.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.support.Utils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.UUID;

/**
 * skillars-deferred-126 AC3 (2026-09-21): without an explicit {@code .withLockedByValue(...)},
 * {@code JdbcTemplateLockProvider} falls back to ShedLock's own hostname-only default identity for
 * {@code locked_by} in {@code main.shedlock}. Two JVMs co-located on the same host — the exact
 * situation during a rolling/blue-green deploy, where an old and a new container can both be briefly
 * up — write the SAME {@code locked_by} value. ShedLock's unlock predicate is {@code WHERE name = :name
 * AND locked_by = :lockedBy}, with no {@code locked_at} component, so an overrunning old instance's
 * still-live lock could be released by the new instance's own unrelated unlock call (or vice versa)
 * purely because they share a {@code locked_by} value — leaving the AC1 stale-claim window as the only
 * remaining protection against a resulting double-run, not a second independent layer.
 *
 * <p>{@code locked_by} is now {@code hostname + "-" + UUID.randomUUID()} — preserves host-level
 * identity for at-a-glance operational debugging in {@code main.shedlock} while guaranteeing per-JVM
 * uniqueness, closing the same-host multi-instance collision this gap describes. "Hostname" here means
 * whatever {@link Utils#getHostname()} reports — in a containerized runtime that may itself be a
 * randomly-generated container ID rather than the host VPS's own name; still valid for uniqueness
 * purposes, just worth knowing when reading {@code main.shedlock} during an incident.
 */
@Slf4j
@Configuration
public class ShedLockConfig {

    public static final String SCHEDULER_LOCK_SKIPPED = "scheduler.lock.skipped";

    /**
     * {@code main.shedlock.locked_by} is {@code character varying(255)} (V138 baseline schema).
     * {@code hostname + "-" + UUID} adds 37 fixed characters for the separator and UUID; an
     * abnormally long hostname (over ~218 chars — rare, but Docker-derived or misconfigured
     * hostnames are not bounded by convention) would otherwise make the lock acquire INSERT/UPDATE
     * fail with SQLState {@code 22001}, breaking EVERY {@code @SchedulerLock}ed job in the app, not
     * just this identity fix.
     */
    private static final int MAX_LOCKED_BY_LENGTH = 255;
    private static final int UUID_SUFFIX_LENGTH = 37; // "-" + UUID.toString() (36 chars)
    // /bmad-code-review fix (2026-09-21): package-private, not private — ShedLockConfigIT (same
    // package) asserted against this bound with a hardcoded `218` literal, which would silently drift
    // out of sync with this computed value if MAX_LOCKED_BY_LENGTH/UUID_SUFFIX_LENGTH ever changed.
    static final int MAX_HOSTNAME_LENGTH = MAX_LOCKED_BY_LENGTH - UUID_SUFFIX_LENGTH;

    @Bean
    public LockProvider lockProvider(DataSource dataSource, MeterRegistry meterRegistry) {
        // skillars-deferred-126 AC3: computed as a local expression inside this @Bean method body,
        // NOT a class-level field. The bean is a Spring singleton, so in production this method runs
        // once — exactly one identity per JVM, matching the original "compute once, not per-lock-
        // acquisition" intent. A class-level field populated once would make two direct test-side
        // calls to this method (bypassing Spring's singleton scoping, since there is no ApplicationContext
        // in a plain unit test) return the SAME value, making ShedLockConfigIT's uniqueness assertion
        // unachievable — computing it here instead makes each direct call produce its own fresh value.
        String hostname = Utils.getHostname();
        String truncatedHostname = hostname.length() > MAX_HOSTNAME_LENGTH
            ? hostname.substring(0, MAX_HOSTNAME_LENGTH)
            : hostname;
        String lockedByValue = truncatedHostname + "-" + UUID.randomUUID();

        LockProvider delegate = new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName("main.shedlock")
                .usingDbTime()
                .withLockedByValue(lockedByValue)
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
