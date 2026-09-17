package com.softropic.skillars.platform.messaging.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-118 AC3: {@code MessageRetentionScheduler.runRetention} had no
 * {@code @SchedulerLock} before this story, unlike every other stateful {@code @Scheduled} job in
 * the codebase. No test file existed for this scheduler before this story either (a plain reflection
 * check is the cheapest way to pin the new annotation — a minimal new file, matching this module's
 * naming convention, rather than exercising the whole bean for an annotation-presence assertion).
 */
class MessageRetentionSchedulerTest {

    @Test
    void runRetention_carriesSchedulerLock() throws NoSuchMethodException {
        Method method = MessageRetentionScheduler.class.getMethod("runRetention");
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        assertThat(lock).as("runRetention() must carry @SchedulerLock").isNotNull();
        assertThat(lock.name()).isNotBlank();
        assertThat(Duration.parse(lock.lockAtMostFor())).isPositive();
        assertThat(Duration.parse(lock.lockAtLeastFor())).isPositive();
    }
}
