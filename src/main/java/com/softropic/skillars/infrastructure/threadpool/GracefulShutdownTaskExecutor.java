package com.softropic.skillars.infrastructure.threadpool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A {@link ThreadPoolTaskExecutor} whose {@code shutdown()} escalates to {@code shutdownNow()} plus a
 * short bounded wait once its {@code awaitTerminationSeconds} budget is spent — the same end state
 * {@link ExecutorShutdown#gracefulFixedPool} already gives the one raw {@code ThreadPoolExecutor}
 * pool (skillars-deferred-99 AC2).
 *
 * <h2>Why this class exists</h2>
 *
 * <p>{@link ExecutorShutdown#configureGracefulShutdown} sets
 * {@code setWaitForTasksToCompleteOnShutdown(true)} + {@code setAwaitTerminationSeconds(n)} on the six
 * {@code ThreadPoolTaskExecutor} pools. Spring's {@code ExecutorConfigurationSupport.shutdown()} then
 * calls {@code executor.shutdown()} and {@code awaitTerminationIfNecessary()} — which waits {@code n}
 * seconds, <strong>logs at WARN, and returns</strong>. A task that outlives the budget keeps running:
 * these are non-daemon workers, so on any context close that is not a JVM exit (a failed
 * {@code @SpringBootTest} context, {@code /actuator/restart}, an embedded-container stop) they run on
 * against a torn-down context with a closed datasource. {@code outboxDrainPool} drains a durable
 * outbox — refunds, transactional email, SLU deltas — exactly the work that must not run
 * half-torn-down.
 *
 * <h2>Budget</h2>
 *
 * <p>The escalation adds at most {@link ExecutorShutdown#FORCED_TERMINATION_SECONDS} second per pool.
 * Bean destruction is sequential, so six pools add ≤ 6 s on top of the ~48 s await budget documented
 * on {@link ExecutorShutdown}, inside the 55 s {@code stop_grace_period} headroom. If you add a pool
 * or lengthen {@code FORCED_TERMINATION_SECONDS}, redo that sum.
 */
public class GracefulShutdownTaskExecutor extends ThreadPoolTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownTaskExecutor.class);

    @Override
    public void shutdown() {
        // Spring's shutdown(): executor.shutdown() then a bounded wait of awaitTerminationSeconds,
        // after which it only logs. Everything below is the missing escalation.
        super.shutdown();

        final ThreadPoolExecutor live;
        try {
            live = getThreadPoolExecutor();
        } catch (IllegalStateException notInitialized) {
            return; // never started — nothing to force
        }
        if (live.isTerminated()) {
            return;
        }

        log.warn("Executor '{}' still draining after its await budget; escalating to shutdownNow() "
                + "({} task(s) queued or running)",
            getThreadNamePrefix(), live.getQueue().size() + live.getActiveCount());
        live.shutdownNow();
        try {
            if (!live.awaitTermination(ExecutorShutdown.FORCED_TERMINATION_SECONDS, TimeUnit.SECONDS)) {
                log.error("Executor '{}' did not terminate even after shutdownNow(); {} task(s) are "
                        + "ignoring interruption and will outlive the application context",
                    getThreadNamePrefix(), live.getQueue().size() + live.getActiveCount());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
