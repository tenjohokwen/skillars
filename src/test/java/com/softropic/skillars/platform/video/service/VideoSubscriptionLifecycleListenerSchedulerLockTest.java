package com.softropic.skillars.platform.video.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-120 AC1: {@code processOutbox()} had no claim mechanism at all (no locking
 * select, no status-flip claim, no {@code @SchedulerLock}, and the backing entity carries no
 * {@code @Version}) — the only outbox-shaped scheduler in the codebase with none of the three
 * standard double-processing protections. {@code @SchedulerLock} closes this by preventing the
 * concurrent invocation entirely.
 *
 * <p>Plain reflection on the annotation — no Spring context, no mocks (code review 2026-09-17,
 * Patch #8: this assertion needs neither and had been sitting in {@code
 * VideoSubscriptionLifecycleListenerIT}, a Testcontainers-backed {@code @SpringBootTest}, which is
 * inconsistent with this codebase's established convention for lock-presence assertions —
 * {@code RadarCompositeDlqProcessorTest}, {@code DeletionSchedulerServiceTest},
 * {@code OutboxPollerSchedulerTest} are all plain unit tests).
 */
class VideoSubscriptionLifecycleListenerSchedulerLockTest {

    @Test
    void processOutbox_carriesSchedulerLock() throws NoSuchMethodException {
        Method method = VideoSubscriptionLifecycleListener.class.getMethod("processOutbox");
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        assertThat(lock).as("processOutbox() must carry @SchedulerLock").isNotNull();
        assertThat(lock.name()).isEqualTo("VideoSubscriptionLifecycleListener_processOutbox");
        // Pinned to the actual sizing values (code review 2026-09-17, Patch #6) — asserting only
        // isPositive() would leave the worst-case arithmetic in the method's Javadoc unguarded.
        assertThat(Duration.parse(lock.lockAtMostFor())).isEqualTo(Duration.ofHours(1));
        assertThat(Duration.parse(lock.lockAtLeastFor())).isEqualTo(Duration.ofSeconds(30));
    }
}
