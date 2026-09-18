package com.softropic.skillars.platform.video.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-120 AC2: {@code findClaimedBatch()} is unscoped to the calling invocation's own
 * claim (global {@code WHERE status = 'CLAIMED'}, no per-run filter) — two concurrent invocations
 * each process rows the other has already claimed, causing real duplicate {@code
 * videoProviderAdapter.deleteAsset} calls. {@code @SchedulerLock} closes this by preventing the
 * concurrent invocation entirely.
 *
 * <p>Plain reflection on the annotation — no Spring context, no mocks (code review 2026-09-17,
 * Patch #8: this assertion needs neither and had been sitting in {@code
 * VideoDeletionOutboxProcessorIT}, a Testcontainers-backed {@code @SpringBootTest}, which is
 * inconsistent with this codebase's established convention for lock-presence assertions —
 * {@code RadarCompositeDlqProcessorTest}, {@code DeletionSchedulerServiceTest},
 * {@code OutboxPollerSchedulerTest} are all plain unit tests).
 */
class VideoDeletionOutboxProcessorSchedulerLockTest {

    @Test
    void process_carriesSchedulerLock() throws NoSuchMethodException {
        Method method = VideoDeletionOutboxProcessor.class.getMethod("process");
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        assertThat(lock).as("process() must carry @SchedulerLock").isNotNull();
        assertThat(lock.name()).isEqualTo("VideoDeletionOutboxProcessor_process");
        // Pinned to the actual sizing values (code review 2026-09-17, Patch #6) — asserting only
        // isPositive() would leave the worst-case arithmetic in the method's Javadoc unguarded.
        // lockAtMostFor="PT15M" per Decision #1: must stay strictly less than
        // VideoDeletionOutboxProcessor.STALE_CLAIM_WINDOW (20 minutes) — see that constant's own
        // Javadoc for why the window was raised rather than the lock lowered to match it.
        assertThat(Duration.parse(lock.lockAtMostFor())).isEqualTo(Duration.ofMinutes(15));
        // skillars-deferred-123 AC4: lockAtLeastFor is now a property expression, not a bare literal
        // (an operator lowering platform.video.deletion.outbox_poll_delay_ms now has a matching knob
        // to raise this floor) — pin the exact expression AND assert the embedded default resolves to
        // a positive duration (code review 2026-09-18 Patch: the latter was previously missing).
        assertThat(lock.lockAtLeastFor()).isEqualTo("${platform.video.deletion.outbox_lock_at_least:PT30S}");
        assertThat(Duration.parse(defaultOf(lock.lockAtLeastFor()))).isPositive();
    }

    /** Extracts the {@code default} out of a {@code ${property:default}} SchedulerLock expression. */
    private static String defaultOf(String springPropertyExpression) {
        String withoutBraces = springPropertyExpression.replace("${", "").replace("}", "");
        return withoutBraces.substring(withoutBraces.indexOf(':') + 1);
    }

    /**
     * skillars-deferred-123 code review 2026-09-18 (Decision 3). The bail-out branch itself needs a
     * clock seam to exercise directly (the budget is 12 minutes), and this story deliberately declined
     * to introduce one — see AC2 Task 3. What is cheaply and usefully testable is the ordering these
     * three constants must satisfy, which is the thing a future edit would actually break:
     *
     * <pre>MAX_RUN_DURATION (12m) &lt; lockAtMostFor (PT15M) &lt; STALE_CLAIM_WINDOW (20m)</pre>
     *
     * <p>Each inequality is load-bearing for a different reason. The left one is what makes the run
     * self-terminate before ShedLock could force-expire the lock, so a second instance can never start
     * while this one is still going. The right one is skillars-deferred-120 Decision 1: the window must
     * outlast the lock, or lock expiry actively triggers the double-processing the lock exists to
     * prevent. Prior to this fix the left inequality did not exist at all and the run's real worst case
     * (BATCH_SIZE 50 x the adapter's 30s read timeout = 25 minutes) exceeded both of the others.
     */
    @Test
    void runtimeBudget_staysStrictlyInsideLockAndStaleWindow() throws Exception {
        Duration maxRun = readDuration("MAX_RUN_DURATION");
        Duration staleWindow = readDuration("STALE_CLAIM_WINDOW");
        Duration lockAtMostFor = Duration.parse(
            VideoDeletionOutboxProcessor.class.getMethod("process")
                .getAnnotation(SchedulerLock.class).lockAtMostFor());

        assertThat(maxRun)
            .as("the run must self-terminate strictly before its own lock can expire")
            .isLessThan(lockAtMostFor);
        assertThat(lockAtMostFor)
            .as("skillars-deferred-120 Decision 1: the stale-claim window must strictly outlast the lock")
            .isLessThan(staleWindow);
    }

    private static Duration readDuration(String fieldName) throws Exception {
        java.lang.reflect.Field field = VideoDeletionOutboxProcessor.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Duration) field.get(null);
    }
}
