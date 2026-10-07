package com.softropic.skillars.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-146 AC1c — pins both halves of
 * {@link DatabaseResetTestExecutionListener#quiesceAsyncExecutors}: the idle fast path must cost
 * essentially nothing, and the waiting path must still actually wait.
 *
 * <p><strong>Why two tests rather than one.</strong> The timing test alone is not enough, and
 * code review 2026-10-07 said so: a wall-clock bound on a shared CI runner can only ever be a
 * loose signal, and an {@code isQuiesced} that regressed to always-true would sail through it.
 * {@link #quiesceAsyncExecutors_busyExecutor_blocksUntilItDrains()} is the non-timing assertion
 * that closes that hole, and it is also the only coverage the {@code pollDelay(Duration.ZERO)}
 * Awaitility branch has — the idle test never reaches it.
 *
 * <p>The timed region covers {@code quiesceAsyncExecutors} only. The executors are constructed
 * and {@link ThreadPoolTaskExecutor#initialize() initialize()}d before the clock starts: not
 * because {@code initialize()} spawns threads (it does not — {@code prestartAllCoreThreads}
 * defaults to false, so {@code ThreadPoolExecutor} creates workers lazily on first submit and
 * this test submits nothing on the idle path), but because it allocates the backing
 * {@code ThreadPoolExecutor} and that setup is not what the budget is about. An earlier revision
 * of this javadoc gave the thread-spawning reason; it was wrong, and is corrected here rather
 * than dropped so the next reader does not reinstate it.
 */
class DatabaseResetTestExecutionListenerQuiesceTest {

    /**
     * 15ms, deliberately tight. The pre-change code paid 6 × 25ms = 150ms of Awaitility poll
     * delay; reverting only the short-circuit while keeping {@code pollDelay(Duration.ZERO)}
     * still costs ~48ms, because that path pays Awaitility's one-off class-load which the idle
     * path never touches. A looser 50ms bound would stop catching that second regression, so the
     * tightness is the point. The sibling test below is what makes the looseness of a wall-clock
     * assertion survivable if this one ever has to be relaxed.
     */
    private static final long IDLE_BUDGET_MILLIS = 15;

    @Test
    void quiesceAsyncExecutors_sixAlreadyIdleExecutors_completesInUnder15Millis() {
        List<ThreadPoolTaskExecutor> executors = new ArrayList<>();
        try {
            fillWithIdleExecutors(executors, 6);
            ApplicationContext ctx = contextOf(executors);
            DatabaseResetTestExecutionListener listener = new DatabaseResetTestExecutionListener();

            long startNanos = System.nanoTime();
            listener.quiesceAsyncExecutors(ctx);
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

            assertThat(elapsedMillis).isLessThan(IDLE_BUDGET_MILLIS);
        } finally {
            executors.forEach(ThreadPoolTaskExecutor::shutdown);
        }
    }

    /**
     * The regression this pins: an {@code isQuiesced} that always returns true, or a re-sweep
     * loop that exits before the pool has drained, would let the reset transaction open while an
     * async writer still holds a row lock — the skillars-deferred-131 deadlock. Asserted on
     * observed state after the call, not on elapsed time, so it cannot flake on a slow runner.
     */
    @Test
    void quiesceAsyncExecutors_busyExecutor_blocksUntilItDrains() throws Exception {
        List<ThreadPoolTaskExecutor> executors = new ArrayList<>();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch taskStarted = new CountDownLatch(1);
        try {
            fillWithIdleExecutors(executors, 1);
            ThreadPoolTaskExecutor busy = executors.get(0);
            busy.execute(() -> {
                taskStarted.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(taskStarted.await(5, TimeUnit.SECONDS))
                .as("the task must be running before the quiesce is asked to wait for it")
                .isTrue();

            ApplicationContext ctx = contextOf(executors);
            DatabaseResetTestExecutionListener listener = new DatabaseResetTestExecutionListener();

            // Hand the quiesce a pool that is genuinely busy, release the task shortly after, and
            // require that the call does not return until the pool has actually drained.
            Thread releaser = new Thread(() -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                release.countDown();
            }, "quiesce-test-releaser");
            releaser.start();

            listener.quiesceAsyncExecutors(ctx);

            assertThat(busy.getActiveCount())
                .as("quiesceAsyncExecutors must not return while a task is still running")
                .isZero();
            assertThat(busy.getThreadPoolExecutor().getQueue())
                .as("quiesceAsyncExecutors must not return with work still queued")
                .isEmpty();
            releaser.join(5_000);
        } finally {
            release.countDown();
            executors.forEach(ThreadPoolTaskExecutor::shutdown);
        }
    }

    /**
     * skillars-deferred-148 Finding 6: {@code isQuiesced}'s second {@code &&} operand,
     * {@code getThreadPoolExecutor()}, throws {@code IllegalStateException} if called before the
     * executor's delegate has been initialized — unreachable through
     * {@code quiesceAsyncExecutors} today (it only ever sees eagerly-initialized Spring beans), but
     * now directly testable since this method was widened to package-private for exactly this.
     */
    @Test
    void isQuiesced_neverInitializedExecutor_returnsTrueWithoutThrowing() {
        ThreadPoolTaskExecutor neverInitialized = new ThreadPoolTaskExecutor();

        assertThat(DatabaseResetTestExecutionListener.isQuiesced(neverInitialized)).isTrue();
    }

    /**
     * Appends to the caller's list as each executor is created, so a failure part-way through
     * still leaves every already-started executor visible to the caller's {@code finally} block.
     * Building the list locally and returning it would leak those threads into the shared
     * surefire JVM (code review 2026-10-07).
     */
    private static void fillWithIdleExecutors(List<ThreadPoolTaskExecutor> sink, int count) {
        for (int i = 0; i < count; i++) {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.initialize();
            sink.add(executor);
        }
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
