package com.softropic.skillars.platform.outbox.config;

import com.softropic.skillars.infrastructure.threadpool.MdcDecorator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    @Bean(name = "outboxDrainPool")
    public Executor outboxDrainPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("outbox-drain-");
        executor.setTaskDecorator(new MdcDecorator());
        // Drop, do not run on the caller: the sweeper is the safety net and no row is ever lost.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
