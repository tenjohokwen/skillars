package com.softropic.skillars.platform.outbox.config;

import com.softropic.skillars.infrastructure.threadpool.ExecutorShutdown;
import com.softropic.skillars.infrastructure.threadpool.MdcDecorator;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Executor for the AFTER_COMMIT outbox drain (skillars-deferred-91 code review, decision D3).
 *
 * <p>Without it, {@code OutboxService.onOutboxDrainRequested} ran on the committing thread, i.e. the
 * HTTP request thread, for up to {@code CHUNK_SIZE * MAX_CHUNKS_PER_DRAIN} rows of blocking SMTP and
 * Stripe I/O — so a backlog accumulated during an outage was drained by whichever user happened to
 * commit next. AC1 requires the drainer to work "off the request path".
 *
 * <p>Deliberately small and single-ish: draining is inherently serial contention on one table, and
 * {@code FOR UPDATE SKIP LOCKED} already lets the scheduled sweeper and other pods work in parallel.
 * The queue is short and {@code CallerRunsPolicy} is <em>not</em> used — falling back to the caller
 * would reintroduce exactly the request-thread blocking this bean exists to remove. A rejected drain
 * is harmless: every unprocessed row stays in the table and the 5-minute sweep picks it up.
 */
@Configuration
public class OutboxConfig {

    /**
     * Production and every non-test profile. {@code matchIfMissing = true} means the property must be
     * explicitly set to {@code false} to disable async draining, which
     * {@code src/test/resources/application-test.yaml} does and nothing else does — the same shape
     * {@code infrastructure.config.SchedulingConfig} uses for {@code app.scheduling.enabled}.
     */
    @Bean(name = "outboxDrainPool")
    @ConditionalOnProperty(name = "app.outbox.drain-async", havingValue = "true", matchIfMissing = true)
    public Executor outboxDrainPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("outbox-drain-");
        executor.setTaskDecorator(new MdcDecorator());
        // Drop, do not run on the caller: the sweeper is the safety net and no row is ever lost.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        // skillars-deferred-92 AC3. The longest slice of the shutdown budget: every queued task here
        // is a drain of a DURABLE outbox — refunds, transactional emails, SLU deltas. shutdownNow()
        // used to discard them silently; the sweeper still recovered the rows, but "recovered on the
        // next boot" is not what an outbox promises. See ExecutorShutdown for the 45 s arithmetic.
        ExecutorShutdown.configureGracefulShutdown(executor, ExecutorShutdown.OUTBOX_DRAIN_SECONDS);
        executor.initialize();
        return executor;
    }

    /**
     * Test profile only: drain on the calling thread so the AFTER_COMMIT effect is visible the moment
     * the producing transaction commits.
     *
     * <p>Integration tests assert on what an outbox drain produced — a credit-wallet ledger row, a
     * weekly-snapshot delta — immediately after the call that triggered it. With a real executor
     * every one of those assertions becomes a race that either needs Awaitility or flakes, and the
     * suite already neutralises background work this way (see {@code SchedulingConfig} and the
     * {@code outbox_initial_delay_ms} override in {@code application-test.yaml}).
     *
     * <p>The trade-off is explicit: the ITs no longer exercise the hand-off to a separate thread.
     * What they do still exercise is everything that matters for correctness — the
     * {@code @TransactionalEventListener(AFTER_COMMIT)} wiring, the per-row {@code REQUIRES_NEW}
     * boundaries, SKIP LOCKED claiming, and the separate-transaction failure bookkeeping. The
     * hand-off itself is one annotation, covered by {@code OutboxServiceAsyncWiringTest}.
     */
    @Bean(name = "outboxDrainPool")
    @ConditionalOnProperty(name = "app.outbox.drain-async", havingValue = "false")
    public Executor outboxDrainPoolSynchronous() {
        return new SyncTaskExecutor();
    }
}
