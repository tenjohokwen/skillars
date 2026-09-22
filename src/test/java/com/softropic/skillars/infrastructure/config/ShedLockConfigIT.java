package com.softropic.skillars.infrastructure.config;

import com.softropic.skillars.config.AbstractIntegrationTest;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
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
    @Autowired private DataSource dataSource;

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

    /**
     * skillars-deferred-126 AC3 Task 6: after acquiring a lock via the real {@code lockProvider} bean,
     * {@code main.shedlock.locked_by} must be neither null/empty nor a bare hostname with no
     * distinguishing suffix — proving the same-host rolling-deploy collision this AC closes cannot
     * recur.
     */
    @Test
    void lockedBy_isNotBlankAndCarriesADistinguishingSuffixBeyondTheBareHostname() {
        String lockName = "test-shed-lock-identity-" + UUID.randomUUID();
        LockConfiguration lockConfiguration =
            new LockConfiguration(Instant.now(), lockName, Duration.ofMinutes(1), Duration.ofSeconds(1));

        Optional<SimpleLock> lock = lockProvider.lock(lockConfiguration);
        assertThat(lock).isPresent();
        try {
            String lockedBy = jdbcTemplate.queryForObject(
                "SELECT locked_by FROM main.shedlock WHERE name = ?", String.class, lockName);

            assertThat(lockedBy).isNotBlank();
            String hostname = net.javacrumbs.shedlock.support.Utils.getHostname();
            // skillars-deferred-127 AC4 Task 5: call the extracted truncateHostname(...) directly
            // rather than re-implementing the truncation rule inline — once truncateHostname gained
            // the surrogate-pair-safety branch, a plain substring() re-implementation here would
            // silently diverge from the real rule for any hostname that happens to trigger it.
            assertThat(lockedBy)
                .as("locked_by must carry a distinguishing suffix beyond the bare hostname, not just "
                    + "the hostname ShedLock's own default identity would have used")
                .isNotEqualTo(hostname)
                .startsWith(ShedLockConfig.truncateHostname(hostname));
        } finally {
            lock.get().unlock();
        }
    }

    /**
     * skillars-deferred-126 AC3 Task 6: two direct calls to the {@code @Configuration} class's {@code
     * lockProvider(...)} method — bypassing Spring's singleton scoping entirely, per Task 3's own
     * design decision to compute the identity inside the method body rather than a class-level field —
     * must return DIFFERENT {@code locked_by} values, proving per-JVM uniqueness rather than a single
     * value shared across every caller.
     */
    @Test
    void lockProviderMethod_twoDirectCallsBypassingSpring_produceDifferentLockedByValues() {
        ShedLockConfig config = new ShedLockConfig();
        LockProvider providerA = config.lockProvider(dataSource, meterRegistry);
        LockProvider providerB = config.lockProvider(dataSource, meterRegistry);

        String lockNameA = "test-shed-lock-identity-a-" + UUID.randomUUID();
        String lockNameB = "test-shed-lock-identity-b-" + UUID.randomUUID();
        LockConfiguration lockConfigA =
            new LockConfiguration(Instant.now(), lockNameA, Duration.ofMinutes(1), Duration.ofSeconds(1));
        LockConfiguration lockConfigB =
            new LockConfiguration(Instant.now(), lockNameB, Duration.ofMinutes(1), Duration.ofSeconds(1));

        Optional<SimpleLock> lockA = providerA.lock(lockConfigA);
        Optional<SimpleLock> lockB = providerB.lock(lockConfigB);
        assertThat(lockA).isPresent();
        assertThat(lockB).isPresent();
        try {
            String lockedByA = jdbcTemplate.queryForObject(
                "SELECT locked_by FROM main.shedlock WHERE name = ?", String.class, lockNameA);
            String lockedByB = jdbcTemplate.queryForObject(
                "SELECT locked_by FROM main.shedlock WHERE name = ?", String.class, lockNameB);

            assertThat(lockedByA)
                .as("two direct calls to lockProvider(...) must each compute their own fresh identity")
                .isNotEqualTo(lockedByB);
        } finally {
            lockA.get().unlock();
            lockB.get().unlock();
        }
    }

    /**
     * skillars-deferred-127 AC4 (story-review.md M1): {@code truncateHostname} must not split a
     * UTF-16 surrogate pair at the truncation boundary. Constructs a hostname whose 218th/219th
     * code units are exactly the surrogate pair a plain {@code substring(0, 218)} would split (a
     * supplementary-plane character, e.g. an emoji) and asserts the result contains no lone
     * surrogate and is a valid UTF-8-encodable string. There is no seam to control
     * {@code Utils.getHostname()}'s return value directly (no mocking precedent needed here —
     * {@code ShedLockConfig.truncateHostname} is package-private and callable directly), so this
     * calls the extracted method with a constructed input rather than trying to control the real
     * hostname.
     */
    @Test
    void truncateHostname_doesNotSplitASurrogatePairAtTheBoundary() {
        // 217 filler chars, then a supplementary-plane code point (U+1F600, 2 UTF-16 code units)
        // straddling indices 217/218 — exactly the boundary MAX_HOSTNAME_LENGTH (218) would split.
        String filler = "h".repeat(ShedLockConfig.MAX_HOSTNAME_LENGTH - 1);
        String emoji = new String(Character.toChars(0x1F600));
        String hostname = filler + emoji + "-extra-tail-beyond-the-cut";
        assertThat(hostname.length()).isGreaterThan(ShedLockConfig.MAX_HOSTNAME_LENGTH);
        // Sanity-check the fixture actually straddles the boundary the way this test claims.
        assertThat(Character.isHighSurrogate(hostname.charAt(ShedLockConfig.MAX_HOSTNAME_LENGTH - 1))).isTrue();
        assertThat(Character.isLowSurrogate(hostname.charAt(ShedLockConfig.MAX_HOSTNAME_LENGTH))).isTrue();

        String truncated = ShedLockConfig.truncateHostname(hostname);

        assertThat(truncated).isEqualTo(filler); // backed off by one, dropping the whole surrogate pair
        assertThat(truncated.length()).isLessThanOrEqualTo(ShedLockConfig.MAX_HOSTNAME_LENGTH);
        for (int i = 0; i < truncated.length(); i++) {
            char c = truncated.charAt(i);
            assertThat(Character.isSurrogate(c))
                .as("truncated hostname must contain no lone surrogate at index %d", i)
                .isFalse();
        }
        // A lone surrogate would still "encode" via Java's default UTF-8 substitution (story-review.md
        // M2 — it does not throw), so the real proof is the absence-of-lone-surrogate check above, not
        // this round-trip; asserted anyway as a cheap extra confirmation of the same fact.
        byte[] utf8 = truncated.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(new String(utf8, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(truncated);
    }

    /**
     * skillars-deferred-127 code review (2026-09-21, Patch): {@code truncateHostname}'s guard
     * requires a WELL-FORMED pair straddling the boundary (high surrogate at {@code
     * MAX_HOSTNAME_LENGTH - 1} AND a matching low surrogate at {@code MAX_HOSTNAME_LENGTH}). A
     * hostname that already carries a lone, unpaired high surrogate exactly at the cut index —
     * pre-existing malformed input, not a pair the truncation itself would split — does not satisfy
     * that second condition, so the guard does not fire and {@code substring(0, 218)} still ends in
     * that lone high surrogate. Fixed by backing off whenever the last character before the cut is
     * ANY high surrogate, regardless of what (if anything) follows it — see
     * {@link ShedLockConfig#truncateHostname}'s own updated implementation.
     */
    @Test
    void truncateHostname_backsOffOnAPreExistingUnpairedHighSurrogateAtTheCutIndex() {
        String filler = "h".repeat(ShedLockConfig.MAX_HOSTNAME_LENGTH - 1);
        // Index MAX_HOSTNAME_LENGTH - 1 is a lone high surrogate; the NEXT character is an ordinary
        // 'x', not its low-surrogate partner — this hostname is already malformed independent of
        // where any truncation would cut it.
        String hostname = filler + '\uD83D' + "x-extra-tail-beyond-the-cut";
        assertThat(Character.isHighSurrogate(hostname.charAt(ShedLockConfig.MAX_HOSTNAME_LENGTH - 1))).isTrue();
        assertThat(Character.isLowSurrogate(hostname.charAt(ShedLockConfig.MAX_HOSTNAME_LENGTH))).isFalse();

        String truncated = ShedLockConfig.truncateHostname(hostname);

        assertThat(truncated).isEqualTo(filler);
        for (int i = 0; i < truncated.length(); i++) {
            assertThat(Character.isSurrogate(truncated.charAt(i)))
                .as("truncated hostname must contain no lone surrogate at index %d", i)
                .isFalse();
        }
    }

    /**
     * /bmad-code-review fix (2026-09-21): the "different instances get different identities" test
     * above asserts the CONVERSE of the invariant that actually matters. Two hand-constructed
     * providers producing different {@code locked_by} values is simply a property of
     * {@code UUID.randomUUID()} being called twice — it says nothing about whether the design
     * achieves "one identity per JVM." What the unlock/extend predicates (both keyed on
     * {@code name = :name AND locked_by = :lockedBy}) actually depend on is the OTHER direction: the
     * SAME bean instance (the single Spring-managed singleton this test autowires, standing in for
     * "one JVM") must write the SAME {@code locked_by} across every lock it takes, not a fresh one
     * per acquisition. A regression to per-acquire identity (e.g. accidentally moving the
     * {@code UUID.randomUUID()} call from the {@code @Bean} method body into the returned lambda)
     * would still pass every other test in this class but would silently defeat this AC's own
     * unlock-safety guarantee, and only this test would catch it.
     */
    @Test
    void lockProviderBean_sameInstanceAcrossTwoAcquisitions_writesTheSameLockedByBothTimes() {
        // main.shedlock.name is character varying(64) — kept short enough (prefix + UUID) to fit,
        // unlike the other lock-name prefixes in this class which have more headroom to spare.
        String lockNameA = "shed-same-a-" + UUID.randomUUID();
        String lockNameB = "shed-same-b-" + UUID.randomUUID();
        LockConfiguration lockConfigA =
            new LockConfiguration(Instant.now(), lockNameA, Duration.ofMinutes(1), Duration.ofSeconds(1));
        LockConfiguration lockConfigB =
            new LockConfiguration(Instant.now(), lockNameB, Duration.ofMinutes(1), Duration.ofSeconds(1));

        // The autowired `lockProvider` is the single Spring-managed singleton bean — exactly the "one
        // JVM, one identity" scenario this AC's own Javadoc describes, unlike the direct-construction
        // test above which deliberately simulates two separate JVMs.
        Optional<SimpleLock> lockA = lockProvider.lock(lockConfigA);
        Optional<SimpleLock> lockB = lockProvider.lock(lockConfigB);
        assertThat(lockA).isPresent();
        assertThat(lockB).isPresent();
        try {
            String lockedByA = jdbcTemplate.queryForObject(
                "SELECT locked_by FROM main.shedlock WHERE name = ?", String.class, lockNameA);
            String lockedByB = jdbcTemplate.queryForObject(
                "SELECT locked_by FROM main.shedlock WHERE name = ?", String.class, lockNameB);

            assertThat(lockedByA)
                .as("the same lockProvider bean instance must write the SAME locked_by across every "
                    + "lock it takes — this is what the unlock/extend predicates actually depend on")
                .isEqualTo(lockedByB);
        } finally {
            lockA.get().unlock();
            lockB.get().unlock();
        }
    }
}
