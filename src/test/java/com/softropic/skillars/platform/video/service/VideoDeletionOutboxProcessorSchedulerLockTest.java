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
        assertThat(Duration.parse(lock.lockAtLeastFor())).isEqualTo(Duration.ofSeconds(30));
    }
}
