package com.softropic.skillars.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-146 AC1c — pins the idle fast path in
 * {@link DatabaseResetTestExecutionListener#quiesceAsyncExecutors}: six already-idle
 * {@link ThreadPoolTaskExecutor} beans must be detected without paying Awaitility's poll delay.
 *
 * <p>The timed region covers {@code quiesceAsyncExecutors} only. The executors are constructed
 * and {@link ThreadPoolTaskExecutor#initialize() initialize()}d before the clock starts, because
 * {@code initialize()} spawns threads and is easily tens of milliseconds on a loaded runner —
 * timing it would make this test flake for a reason unrelated to what it asserts.
 */
class DatabaseResetTestExecutionListenerQuiesceTest {

    @Test
    void quiesceAsyncExecutors_sixAlreadyIdleExecutors_completesInUnder15Millis() {
        List<ThreadPoolTaskExecutor> executors = sixIdleExecutors();
        ApplicationContext ctx = contextOf(executors);
        DatabaseResetTestExecutionListener listener = new DatabaseResetTestExecutionListener();

        try {
            long startNanos = System.nanoTime();
            listener.quiesceAsyncExecutors(ctx);
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

            assertThat(elapsedMillis).isLessThan(15);
        } finally {
            executors.forEach(ThreadPoolTaskExecutor::shutdown);
        }
    }

    private static List<ThreadPoolTaskExecutor> sixIdleExecutors() {
        List<ThreadPoolTaskExecutor> executors = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.initialize();
            executors.add(executor);
        }
        return executors;
    }

    private static ApplicationContext contextOf(List<ThreadPoolTaskExecutor> executors) {
        Map<String, ThreadPoolTaskExecutor> beans = new LinkedHashMap<>();
        for (int i = 0; i < executors.size(); i++) {
            beans.put("executor" + i, executors.get(i));
        }
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(ThreadPoolTaskExecutor.class)).thenReturn(beans);
        return ctx;
    }
}
