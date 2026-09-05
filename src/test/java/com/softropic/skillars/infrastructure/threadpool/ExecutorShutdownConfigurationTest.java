package com.softropic.skillars.infrastructure.threadpool;

import com.softropic.skillars.platform.development.config.DevelopmentConfig;
import com.softropic.skillars.platform.outbox.config.OutboxConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-92 AC3 — every {@link ThreadPoolTaskExecutor} in the application drains on
 * shutdown instead of being killed, and the whole set of pools is known.
 *
 * <p>Before this story, {@code grep -rn "setWaitForTasksToCompleteOnShutdown\|setAwaitTerminationSeconds"}
 * over {@code src/main/java} returned zero hits while five pools existed, so
 * {@code ExecutorConfigurationSupport.destroy()} called {@code shutdownNow()} on all of them:
 * queued tasks discarded, in-flight {@code @Backoff} sleeps interrupted with an exception matching no
 * {@code retryFor}/{@code @Recover}. skillars-deferred-91 then added {@code outboxDrainPool}, which
 * drains a durable outbox — at which point a project convention became a reliability defect.
 *
 * <p><strong>Deliberately not a {@code @SpringBootTest}.</strong> AC3.4 allows "a focused config
 * test", and this project gates its Spring test-context count in CI. Instantiating the four
 * {@code @Configuration} classes directly costs no context and no container, and it side-steps a real
 * trap: under the test profile {@code app.outbox.drain-async=false}, so the {@code outboxDrainPool}
 * bean Spring would hand a {@code @SpringBootTest} is a {@code SyncTaskExecutor} with none of these
 * methods on it. Calling {@code OutboxConfig#outboxDrainPool()} directly always yields the real pool.
 *
 * <p><strong>{@code isWaitForTasksToCompleteOnShutdown()} does not exist.</strong> AC3.4 asks for it
 * by name, but {@code ExecutorConfigurationSupport} (6.2.19) exposes only setters for both fields —
 * verified by {@code javap}. The assertions therefore read the private fields reflectively, which is
 * the only way to observe the configured value without actually shutting a pool down.
 */
@DisplayName("Every ThreadPoolTaskExecutor bean must be configured for graceful shutdown")
class ExecutorShutdownConfigurationTest {

    /**
     * The complete set of pools, with the await each is expected to carry. Keyed by bean name so a
     * failure names the bean rather than a config method.
     *
     * <p>{@code DevelopmentConfig} takes an {@code EntityManager} only for its {@code @PostConstruct}
     * schema probe, which this test never invokes — {@code null} is safe and deliberate.
     */
    private static Map<String, ThreadPoolTaskExecutor> pools() {
        Map<String, ThreadPoolTaskExecutor> pools = new LinkedHashMap<>();
        pools.put("outboxDrainPool", (ThreadPoolTaskExecutor) new OutboxConfig().outboxDrainPool());
        pools.put("sluRetryExecutor", new DevelopmentConfig(null).sluRetryExecutor());
        pools.put("moderationTaskExecutor",
            (ThreadPoolTaskExecutor) new com.softropic.skillars.platform.notification.config.AsyncConfig()
                .moderationTaskExecutor());
        pools.put("sendMailPool",
            (ThreadPoolTaskExecutor) new com.softropic.skillars.platform.notification.config.AsyncConfig()
                .threadPoolTaskExecutor());
        pools.put("taskExecutor",
            new com.softropic.skillars.infrastructure.config.AsyncConfig().taskExecutor());
        pools.put("reportExecutor", new DevelopmentConfig(null).reportExecutor());
        return pools;
    }

    private static final Map<String, Integer> EXPECTED_AWAIT_SECONDS = Map.of(
        "outboxDrainPool", ExecutorShutdown.OUTBOX_DRAIN_SECONDS,
        "sluRetryExecutor", ExecutorShutdown.SLU_RETRY_SECONDS,
        "moderationTaskExecutor", ExecutorShutdown.MODERATION_SECONDS,
        "sendMailPool", ExecutorShutdown.SEND_MAIL_SECONDS,
        "taskExecutor", ExecutorShutdown.SHARED_ASYNC_SECONDS,
        "reportExecutor", ExecutorShutdown.REPORT_SECONDS);

    private static Object field(Object target, String name) {
        Field f = ReflectionUtils.findField(target.getClass(), name);
        assertThat(f).as("ExecutorConfigurationSupport.%s must still exist", name).isNotNull();
        ReflectionUtils.makeAccessible(f);
        return ReflectionUtils.getField(f, target);
    }

    @Test
    @DisplayName("all six ThreadPoolTaskExecutor pools wait for queued tasks and have a bounded await")
    void everyPoolDrainsOnShutdown() {
        pools().forEach((name, pool) -> {
            assertThat(field(pool, "waitForTasksToCompleteOnShutdown"))
                .as("%s calls shutdownNow() on context close, discarding queued work", name)
                .isEqualTo(true);
            assertThat((Long) field(pool, "awaitTerminationMillis"))
                .as("%s waits for tasks but never blocks for them, so destroy() returns immediately "
                    + "and the JVM exits mid-task — the flag alone achieves nothing", name)
                .isEqualTo(EXPECTED_AWAIT_SECONDS.get(name) * 1000L);
        });
    }

    /**
     * AC3.3. {@code sendMailPool} called {@code setRejectedExecutionHandler} <em>after</em>
     * {@code afterPropertiesSet()}. {@code afterPropertiesSet()} → {@code initialize()} builds the
     * underlying {@code ThreadPoolExecutor} from the fields set so far, so the later setter mutated
     * the Spring wrapper and never reached the live executor: the pool ran with the JDK default
     * {@code AbortPolicy} and a full queue threw {@code RejectedExecutionException} at the caller
     * instead of sending the mail inline.
     *
     * <p>The assertion deliberately reads {@code getThreadPoolExecutor().getRejectedExecutionHandler()}
     * — the <em>live</em> executor — because the wrapper's own field was correct even while the bug
     * was live. A test against the wrapper would have passed against the broken code.
     */
    @Test
    @DisplayName("sendMailPool's CallerRunsPolicy actually reaches the live executor")
    void sendMailPoolRejectedExecutionHandler_isCallerRuns() {
        ThreadPoolTaskExecutor sendMailPool = pools().get("sendMailPool");

        assertThat(sendMailPool.getThreadPoolExecutor().getRejectedExecutionHandler())
            .as("a full sendMailPool queue must run the send on the caller, not throw at it")
            .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    }

    /** The correctly-ordered neighbour, pinned so the two cannot silently diverge again. */
    @Test
    @DisplayName("moderationTaskExecutor's CallerRunsPolicy also reaches the live executor")
    void moderationTaskExecutorRejectedExecutionHandler_isCallerRuns() {
        assertThat(pools().get("moderationTaskExecutor").getThreadPoolExecutor().getRejectedExecutionHandler())
            .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    }

    /**
     * AC3.4's real requirement: "a sixth pool added later without it is caught by intent, not by
     * luck". Scanning the compiled {@code @Configuration} classes means a new pool fails the build
     * until someone adds it to {@link #pools()} and gives it a slice of the shutdown budget — the
     * list above cannot silently fall behind the code.
     *
     * <p>Scope, stated rather than implied: this finds {@code @Bean} methods on {@code @Configuration}
     * classes whose declared return type is an {@code Executor}/{@code TaskExecutor}. A pool created
     * outside a {@code @Bean} method, or behind a return type that hides it (e.g. {@code Object}),
     * would not be seen. That is a deliberate limit, not an oversight — every current pool is
     * declared the conventional way.
     */
    @Test
    @DisplayName("no executor @Bean exists that this test does not know about")
    void everyExecutorBeanIsCovered() throws Exception {
        List<String> discovered = new ArrayList<>();
        var resolver = new PathMatchingResourcePatternResolver();
        var readers = new CachingMetadataReaderFactory(resolver);

        for (var resource : resolver.getResources(
                "classpath*:com/softropic/skillars/**/*Config.class")) {
            var metadata = readers.getMetadataReader(resource).getAnnotationMetadata();
            if (!metadata.hasAnnotation(Configuration.class.getName())) {
                continue;
            }
            Class<?> configClass = ClassUtils.forName(metadata.getClassName(), getClass().getClassLoader());
            for (Method m : configClass.getDeclaredMethods()) {
                if (m.getAnnotation(Bean.class) == null) {
                    continue;
                }
                Class<?> returned = m.getReturnType();
                if (Executor.class.isAssignableFrom(returned) || TaskExecutor.class.isAssignableFrom(returned)) {
                    discovered.add(configClass.getSimpleName() + "#" + m.getName());
                }
            }
        }

        assertThat(discovered)
            .as("""
                An executor @Bean was added or removed. Every ThreadPoolTaskExecutor in this \
                application must take a slice of the shutdown budget in \
                infrastructure.threadpool.ExecutorShutdown, be listed in this test's pools() map, \
                and be accounted for in docker-compose's stop_grace_period arithmetic. Update all \
                three, then update this list.""")
            .containsExactlyInAnyOrder(
                "OutboxConfig#outboxDrainPool",
                // The test-profile twin: a SyncTaskExecutor, mutually exclusive with the pool above
                // by @ConditionalOnProperty. It has no threads and nothing to drain.
                "OutboxConfig#outboxDrainPoolSynchronous",
                "DevelopmentConfig#sluRetryExecutor",
                // skillars-deferred-92 code review D2. Report/radar generation used to sit on the
                // shared taskExecutor and its 2s slice while doing an S3 upload with no retry behind
                // it; ExecutorShutdown.REPORT_SECONDS documents why it has its own 8s slice instead.
                "DevelopmentConfig#reportExecutor",
                "AsyncConfig#moderationTaskExecutor",
                "AsyncConfig#threadPoolTaskExecutor",
                "AsyncConfig#taskExecutor",
                // The sixth pool, and the reason this scan exists. A raw ThreadPoolExecutor rather
                // than a ThreadPoolTaskExecutor, so it is invisible to the grep the story's AC3
                // inventory was built from. Covered by storageUploadExecutorAwaitsOnShutdown below,
                // not by pools() — it is not a ThreadPoolTaskExecutor and has no such fields.
                "BlobstoreConfig#storageUploadExecutor");
    }

    /**
     * The sixth pool cannot be asserted through {@code ThreadPoolTaskExecutor}'s fields — it is a raw
     * {@link ThreadPoolExecutor} — so this asserts the behaviour directly: {@code shutdown()} must
     * block until the running task finishes. The plain {@code ExecutorService.shutdown()} contract is
     * non-blocking, which is exactly the bug: {@code destroy()} returned instantly and the JVM exited
     * with S3 uploads in flight.
     *
     * <p>The latch makes the assertion load-bearing: against the previous non-blocking
     * {@code shutdown()} the task would still be running when the assertion ran, and it would fail.
     */
    @Test
    @DisplayName("storageUploadExecutor's shutdown() waits for the in-flight upload")
    void storageUploadExecutorAwaitsOnShutdown() throws Exception {
        ExecutorService pool = ExecutorShutdown.gracefulFixedPool(1, 4, "test-storage-upload-", 5);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean(false);

        pool.submit(() -> {
            started.countDown();
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            finished.set(true);
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).as("task must have started").isTrue();

        pool.shutdown();

        assertThat(finished)
            .as("shutdown() must not return while a task is still running — the whole point of AC3")
            .isTrue();
        assertThat(pool.isTerminated()).isTrue();
    }

    /**
     * skillars-deferred-92 code review. The bounded wait used to be the whole of {@code shutdown()}:
     * a task that outlived the budget was logged about and then left running. These are non-daemon
     * threads, so on any context close that is not a JVM exit they carried on against a torn-down
     * context. {@code shutdown()} must now interrupt them.
     *
     * <p>Load-bearing by construction: the task sleeps far longer than the 1-second budget, so
     * without the {@code shutdownNow()} this assertion sees a pool that is still not terminated.
     */
    @Test
    @DisplayName("shutdown() interrupts tasks that outlive the pool's budget")
    void gracefulFixedPool_forcesTerminationAfterItsBudget() throws Exception {
        ExecutorService pool = ExecutorShutdown.gracefulFixedPool(1, 4, "test-overrun-", 1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);

        pool.submit(() -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interrupted.set(true);
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).as("task must have started").isTrue();

        pool.shutdown();

        assertThat(pool.isTerminated())
            .as("after its bounded wait expires, shutdown() must shutdownNow() rather than leave "
                + "non-daemon workers running against a torn-down context")
            .isTrue();
        assertThat(interrupted).as("the overrunning task must have been interrupted").isTrue();
    }
}
