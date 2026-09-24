package com.softropic.skillars.utils;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.awaitility.Awaitility;

import java.time.Duration;

/**
 * skillars-deferred-131 AC3. Deterministic "contention established" signals, replacing a fixed
 * {@code Thread.sleep(300)} across {@code ReviewFlagServiceConcurrencyIT},
 * {@code CoachProfileServiceConcurrencyIT} and {@code SubscriptionServiceConcurrencyIT}. A fixed sleep
 * proves nothing — on a cold JVM or a constrained CI runner the contending thread might not have
 * reached the lock yet (a false pass), and on a loaded system the sleep can outlast what the test
 * actually needs (a slow, still-correct run).
 *
 * <p>Every contending call site this class supports takes its lock via
 * {@code jakarta.persistence.lock.timeout = "0"} (NOWAIT) — the contending backend never enters a
 * Postgres wait state, it fails instantly and {@link com.softropic.skillars.infrastructure.persistence
 * .PessimisticLockRetryer} retries in Java, so a live {@code pg_locks} poll for a waiter would spin
 * until timeout and fail even on a correct system.
 *
 * <p><strong>skillars-deferred-131 implementation-time correction, still true after
 * skillars-deferred-132's per-call-site tagging:</strong> {@code PessimisticLockRetryer}'s own
 * {@code persistence.lock_retry.retries} counter cannot gate a PRE-release wait — {@code
 * recordRetries(...)} only fires once the whole retry loop CONCLUDES (on eventual success, or on
 * exhaustion), not per individual failed attempt, so polling it to become {@code > baseline} before
 * releasing the holder's lock would either return immediately (a stale value from an unrelated
 * earlier call) or block until the contender has already given up — never a live "still retrying"
 * signal. Use {@link #FIRST_LOCK_ATTEMPT_DELAY} as a short, bounded pre-release delay instead (long
 * enough for the NOWAIT failure and its first backoff to have genuinely happened), paired with
 * {@link #assertGenuineLockRetryOccurred} AFTER both threads join — a real, deterministic post-hoc
 * proof that contention was actually hit and retried, not merely assumed from timing.
 *
 * <p><strong>skillars-deferred-132 AC1 Fix 2 history:</strong> until this story, {@code
 * CoachReviewRepository.findByIdForUpdate} carried no {@code @QueryHints} — a genuine blocking
 * {@code FOR UPDATE} — and this class exposed a second, {@code pg_locks}-polling helper
 * ({@code awaitBlockingLockWaiter}) for the tests contending on it. {@code ReviewFlagService.flag()}
 * (the only caller that needed it) was rewritten to use a new NOWAIT-only
 * {@code findByIdForUpdateNoWait} method instead (see {@code CoachReviewRepository}'s own comment for
 * why {@code findByIdForUpdate} itself stays blocking for its other five call sites), leaving that
 * helper with zero remaining callers in this codebase — removed rather than left as dead code.
 */
public final class ConcurrencyLockWaitSupport {

    /**
     * A short, bounded delay giving a NOWAIT contender time to make its first failed attempt (an
     * instant {@code 55P03}, no network round trip to wait out) and begin its jittered backoff
     * (75-100ms floor at the default {@code initial-backoff-ms=100}) before the holder releases its
     * lock. See the class javadoc for why this cannot be replaced with a live poll.
     *
     * <p>Code review 2026-09-23: kept at the same 300ms this replaced (the fixed
     * {@code Thread.sleep(300)} this class's javadoc describes), not halved to 150ms. This delay is now
     * paired with {@link #assertGenuineLockRetryOccurred}'s hard post-hoc assertion — a contender that
     * has not yet made its first attempt by the time the holder releases is a genuine test failure, not
     * a silently-tolerated pass the way it was pre-AC3. Trimming the delay below the value already
     * proven reliable would trade a real determinism improvement for a new timing-flake surface on a
     * loaded CI runner, for no benefit this class needs.
     */
    private static final Duration FIRST_LOCK_ATTEMPT_DELAY = Duration.ofMillis(300);

    private ConcurrencyLockWaitSupport() {
    }

    /** Sleeps for {@link #FIRST_LOCK_ATTEMPT_DELAY}. See the class javadoc's NOWAIT discussion. */
    public static void awaitFirstLockAttempt() throws InterruptedException {
        Thread.sleep(FIRST_LOCK_ATTEMPT_DELAY.toMillis());
    }

    /**
     * The current {@code persistence.lock_retry.retries} counter value FOR ONE {@code lockName} tag,
     * to capture as a baseline before starting a contended call. Pre-skillars-deferred-132 this counter
     * was untagged and JVM-wide across every
     * {@link com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer} call site and
     * every test in the run — an unrelated in-flight retry elsewhere in the same JVM could satisfy a
     * bare {@code > baseline} poll without the intended site ever retrying. Tagging by {@code lockName}
     * (skillars-deferred-132 AC1 Fix 5) closes that: this looks up only the counter registered with
     * {@code lock=lockName}, matching the exact string the call site under test passes to
     * {@code PessimisticLockRetryer.withBoundedRetry(lockName, ...)}.
     */
    public static double currentLockRetryCount(MeterRegistry meterRegistry, String lockName) {
        Counter counter = meterRegistry.find("persistence.lock_retry.retries").tag("lock", lockName).counter();
        return counter == null ? 0.0 : counter.count();
    }

    /**
     * Asserts (via a short bounded poll, to absorb the meter's own recording happening on whichever
     * thread finishes last) that {@code persistence.lock_retry.retries} tagged {@code lock=lockName}
     * has grown past {@code baseline} — direct, deterministic, POST-HOC proof THAT SPECIFIC call site's
     * NOWAIT contender genuinely hit the held lock and retried at least once, rather than racing past it
     * uncontended (or an unrelated retry elsewhere in the JVM satisfying a looser, untagged assertion).
     * Call this AFTER both the holder and the contender threads have been joined.
     */
    public static void assertGenuineLockRetryOccurred(MeterRegistry meterRegistry, String lockName, double baseline) {
        Awaitility.await()
            .atMost(Duration.ofSeconds(5))
            .pollInterval(Duration.ofMillis(25))
            .until(() -> currentLockRetryCount(meterRegistry, lockName) > baseline);
    }
}
