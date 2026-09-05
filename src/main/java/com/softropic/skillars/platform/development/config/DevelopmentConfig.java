package com.softropic.skillars.platform.development.config;

import com.softropic.skillars.infrastructure.exception.AppSetupException;
import com.softropic.skillars.infrastructure.threadpool.ExecutorShutdown;
import com.softropic.skillars.infrastructure.threadpool.MdcDecorator;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Configuration
public class DevelopmentConfig {

    private final EntityManager entityManager;

    public DevelopmentConfig(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    // Guards against a silent schema/column drift between SluRepository's native queries and the
    // development.player_skill_stats table (see V46__development_module_init.sql). A mismatch would
    // otherwise surface as a runtime BadSqlGrammarException on the first SLU read, not at startup.
    @PostConstruct
    void validateSluRepositorySchema() {
        try {
            entityManager.createNativeQuery(
                "SELECT slu_value, calculated_at FROM development.player_skill_stats LIMIT 0")
                .getResultList();
            log.info("SluRepository schema validation passed");
        } catch (RuntimeException e) {
            log.error("SluRepository schema validation failed — column names may not match migration", e);
            throw new AppSetupException("SluRepository schema mismatch: " + e.getMessage());
        }
    }

    /**
     * Dedicated bounded executor for the two SLU persistence retriers (skillars-deferred-86 AC3).
     *
     * <p>{@code SluCalculationService.onBookingCompleted} runs on the shared {@code @Async}
     * {@code taskExecutor} listener pool. Before this bean it also ran the retriers'
     * {@code @Backoff} sleeps there, so a DB hiccup during a burst of booking completions parked
     * listener threads and stalled SLU/snapshot processing for every other player. The
     * {@link com.softropic.skillars.platform.development.service.SluPersistenceDispatcher}'s single
     * {@code @Async("sluRetryExecutor")} method now runs that persistence chain here instead, and
     * the listener thread returns immediately.
     *
     * <p>Sizing: {@code core 2 / max 4 / queue 10}. A {@link ThreadPoolTaskExecutor} only grows past
     * {@code corePoolSize} once the queue is full, so the queue is deliberately small — with a
     * 50-deep queue threads 3-4 would not engage until ~50 tasks are backlogged (~8 s of latency
     * accumulating on 2 threads under a sustained outage). {@code allowCoreThreadTimeOut(true)} lets
     * the pool idle back to 0 between bursts.
     *
     * <p>This bulkhead <strong>bounds</strong> rather than <strong>eliminates</strong> listener-thread
     * stalls: once {@code sluRetryExecutor} itself saturates, {@code CallerRunsPolicy} runs the task
     * on the calling ({@code taskExecutor} listener) thread — a deliberate fall-back to the
     * pre-story behaviour (backpressure, not dropped SLU writes; {@code AbortPolicy} would drop them).
     */
    // public (skillars-deferred-92 AC3) so ExecutorShutdownConfigurationTest can build the real pool
    // without a Spring context, exactly as it does for the other four. Matches its four siblings.
    @Bean(name = "sluRetryExecutor")
    public ThreadPoolTaskExecutor sluRetryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("slu-retry-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(task -> new MdcDecorator().decorate(task));
        // skillars-deferred-92 AC3: without this, shutdownNow() interrupted the @Backoff sleep of an
        // in-flight retry with an InterruptedException that matches no retryFor/@Recover, so the SLU
        // write was lost through a code path that reported nothing. See ExecutorShutdown.
        ExecutorShutdown.configureGracefulShutdown(executor, ExecutorShutdown.SLU_RETRY_SECONDS);
        executor.initialize();
        return executor;
    }

    /**
     * The development module's long-running {@code @Async} listeners (skillars-deferred-92 code
     * review, decision D2).
     *
     * <p>{@code ReportGenerationService.onReportGenerated} (S3 upload of a built PDF, then a status
     * flip, a timeline write and a parent notification) and
     * {@code RadarCompositeCalculationService.onRadarEntrySubmitted} (pessimistic player-row lock for
     * a read-then-upsert) both ran on the shared {@code taskExecutor}, whose shutdown slice is
     * {@value com.softropic.skillars.infrastructure.threadpool.ExecutorShutdown#SHARED_ASYNC_SECONDS}
     * seconds precisely because nothing on it is supposed to be individually long-running. An S3
     * round trip is, and the report path has no outbox row and no retry behind it — a report
     * abandoned mid-upload stays {@code PENDING_UPLOAD} forever and never appears in
     * {@code listReports}. Its own pool with its own
     * {@value com.softropic.skillars.infrastructure.threadpool.ExecutorShutdown#REPORT_SECONDS}-second
     * slice is what makes the shared pool's documented assumption true again.
     *
     * <p>Sizing: {@code core 2 / max 4 / queue 50}. {@code CallerRunsPolicy} rather than a discard
     * policy — a dropped task here is a permanently broken report, so backpressure onto the
     * committing thread is the lesser harm — and {@code allowCoreThreadTimeOut(true)} so the pool
     * idles back to zero between the bursts these two listeners actually arrive in.
     */
    @Bean(name = "reportExecutor")
    public ThreadPoolTaskExecutor reportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("report-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(task -> new MdcDecorator().decorate(task));
        ExecutorShutdown.configureGracefulShutdown(executor, ExecutorShutdown.REPORT_SECONDS);
        executor.initialize();
        return executor;
    }
}
