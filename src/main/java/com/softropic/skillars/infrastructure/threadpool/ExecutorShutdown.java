package com.softropic.skillars.infrastructure.threadpool;

import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shutdown budget for every {@link ThreadPoolTaskExecutor} in the application (skillars-deferred-92 AC3).
 *
 * <h2>The problem this closes</h2>
 *
 * Before this class, {@code grep -rn "setWaitForTasksToCompleteOnShutdown\|setAwaitTerminationSeconds"}
 * over {@code src/main/java} returned <strong>zero</strong> hits while five pools existed. On context
 * close {@code ExecutorConfigurationSupport.destroy()} therefore called {@code shutdownNow()}: queued
 * tasks were discarded outright, in-flight {@code @Backoff} sleeps took an {@code InterruptedException}
 * that matches no {@code retryFor} / {@code @Recover}, and {@link MdcDecorator} logged the result as a
 * generic "Exception thrown from detached thread".
 *
 * <p>That was arguably tolerable while the pools carried best-effort work. skillars-deferred-91 added
 * {@code outboxDrainPool}, which drains a <em>durable outbox</em> — refunds, transactional emails, SLU
 * snapshot deltas. Abandoning those drains does not lose the rows (the 5-minute sweeper re-drives
 * them) but it abandons them <em>silently</em>, and "a row is never lost" is the outbox's whole
 * reason to exist.
 *
 * <h2>Why the budget is expressed in wall-clock seconds against {@code stop_grace_period}</h2>
 *
 * <p><strong>Correcting the premise this AC was written on.</strong> The story asserted that
 * {@code ExecutorConfigurationSupport} "is a {@code DisposableBean}, not a {@code SmartLifecycle}",
 * citing Spring Framework 6.2.12. Both halves are wrong for this project: the resolved version is
 * <strong>6.2.19</strong> (Boot 3.5.16), and since 6.1 the class implements
 * {@code BeanNameAware, ApplicationContextAware, InitializingBean, DisposableBean, SmartLifecycle,
 * ApplicationListener<ContextClosedEvent>} — it is <em>both</em>. Verified by reading
 * {@code spring-context-6.2.19-sources.jar}, not inferred.
 *
 * <p>The <em>conclusion</em> survives, by a more specific mechanism, and it is worth stating exactly
 * because it is the whole justification for the numbers below:
 *
 * <ol>
 *   <li>{@code onApplicationEvent(ContextClosedEvent)} checks {@code waitForTasksToCompleteOnShutdown}.
 *       Because {@link #configureGracefulShutdown} sets it {@code true}, the branch taken is
 *       {@code lateShutdown = true} — the "late shutdown without early stop lifecycle" path.</li>
 *   <li>{@code stop()} and {@code stop(Runnable)} both begin
 *       {@code if (lifecycleDelegate != null && !lateShutdown)}. With {@code lateShutdown} set, they
 *       do nothing; {@code stop(Runnable)} runs its callback immediately. So the pools consume
 *       <strong>none</strong> of {@code spring.lifecycle.timeout-per-shutdown-phase} and do not stall
 *       that phase either.</li>
 *   <li>The blocking wait is therefore reached only via {@code destroy()} → {@code shutdown()} →
 *       {@code awaitTerminationIfNecessary()}, during ordinary bean destruction, which happens after
 *       every lifecycle phase and is bounded by nothing inside Spring.</li>
 * </ol>
 *
 * The only real bound is the container runtime: {@code docker-compose.yml} sets
 * {@code stop_grace_period} on the {@code app} service, after which Docker sends SIGKILL and every
 * remaining await is irrelevant. Hence the arithmetic below. (Aside: the executors' lifecycle phase
 * is {@code DEFAULT_PHASE = Integer.MAX_VALUE / 2}, below the web server's, so even without
 * {@code lateShutdown} they would stop after the HTTP drain rather than concurrently with it.)
 *
 * <p><strong>Signal delivery is verified, not assumed.</strong> {@code Dockerfile:45} is
 * {@code ENTRYPOINT ["java", "-jar", "app.jar"]} — exec form, so the JVM is PID 1 and receives SIGTERM
 * directly rather than having it swallowed by a shell wrapper. The JVM installs its own SIGTERM
 * handler at startup (PID 1's missing default handlers do not apply to explicitly-installed ones), so
 * Spring's shutdown hook does run and everything below has effect. Without this the whole AC would be
 * theatre, which is why it is recorded here rather than left to a reader to re-derive.
 *
 * <h2>The arithmetic</h2>
 *
 * Bean destruction is sequential, so the worst case is the <em>sum</em> of every pool's await, not the
 * maximum. Graceful web shutdown runs before it, in its own phase:
 *
 * <pre>
 *   HTTP in-flight drain (spring.lifecycle.timeout-per-shutdown-phase)   8 s
 *   outboxDrainPool         {@value #OUTBOX_DRAIN_SECONDS} s
 *   sluRetryExecutor        {@value #SLU_RETRY_SECONDS} s
 *   storageUploadExecutor   {@value #STORAGE_UPLOAD_SECONDS} s
 *   sendMailPool            {@value #SEND_MAIL_SECONDS} s
 *   moderationTaskExecutor  {@value #MODERATION_SECONDS} s
 *   taskExecutor            {@value #SHARED_ASYNC_SECONDS} s
 *   reportExecutor          {@value #REPORT_SECONDS} s
 *   remaining context teardown (datasource, Flyway, Redis)             ~4 s
 *   -------------------------------------------------------------------------
 *   worst case                                                        ~48 s
 *   docker-compose stop_grace_period (app service)                     55 s
 * </pre>
 *
 * {@code stop_grace_period} was raised from 30 s to 45 s for this budget, then to 55 s when
 * {@link #REPORT_SECONDS} added a seventh pool; at 30 s the sum above could not fit and the pools
 * would have been SIGKILLed mid-drain anyway, which is the state this class exists to leave behind.
 *
 * <p><strong>There are seven pools, not the five the story enumerated.</strong>
 * {@code BlobstoreConfig#storageUploadExecutor} is a raw {@link java.util.concurrent.ThreadPoolExecutor}
 * rather than a {@code ThreadPoolTaskExecutor}, which is why an inventory built by grepping for
 * {@code ThreadPoolTaskExecutor} missed it. Its {@code @Bean(destroyMethod = "shutdown")} was better
 * than the others' {@code shutdownNow()} — queued S3 uploads were kept rather than discarded — but
 * {@code ExecutorService.shutdown()} does not block, so {@code destroy()} returned instantly and the
 * JVM exited with those uploads still in flight. (Once shutdown has begun the JVM terminates when its
 * hooks finish; it does not wait on remaining non-daemon threads.) {@link #gracefulFixedPool} closes
 * that. It was found by {@code ExecutorShutdownConfigurationTest#everyExecutorBeanIsCovered} on its
 * very first run — which is precisely the "caught by intent, not by luck" that AC3.4 asked for.
 * {@code reportExecutor} is the seventh and was added by this story's own code review; see
 * {@link #REPORT_SECONDS}.
 *
 * <p><strong>If you add an eighth pool or lengthen an await, redo this sum and move
 * {@code stop_grace_period} with it.</strong> The test fails the build on a pool that is not
 * configured here, but it cannot know what the container will allow.
 */
public final class ExecutorShutdown {

    /**
     * Longest, and deliberately so: each queued task is a full outbox drain doing blocking SMTP and
     * Stripe I/O, and every row it does not finish is a refund or a notification a committed
     * transaction promised. Bounded rather than unlimited because the 5-minute sweeper is the safety
     * net — this buys the common case, it is not the durability guarantee.
     */
    public static final int OUTBOX_DRAIN_SECONDS = 10;

    /**
     * Long enough for one in-flight SLU/snapshot batch plus a {@code @Backoff} sleep to land. The
     * queue is only 10 deep and each task is a short DB write.
     */
    public static final int SLU_RETRY_SECONDS = 5;

    /** One in-flight SMTP send, plus the short queue behind it. */
    public static final int SEND_MAIL_SECONDS = 4;

    /** Moderation work is re-drivable from the SLA monitor; a short drain is enough. */
    public static final int MODERATION_SECONDS = 2;

    /**
     * The shared {@code @Async} pool: many short tasks, nothing individually long-running.
     *
     * <p>That second clause is a <em>constraint on what may be routed here</em>, not an observation.
     * Anything with a task that can outlast two seconds belongs on its own pool with its own slice —
     * see {@link #REPORT_SECONDS} for the case that forced the distinction.
     */
    public static final int SHARED_ASYNC_SECONDS = 2;

    /**
     * {@code reportExecutor} — the development module's long-running {@code @Async} work
     * (skillars-deferred-92 code review, decision D2).
     *
     * <p>{@code ReportGenerationService.onReportGenerated} does an S3 upload of a built PDF followed
     * by a status flip, a timeline write and a parent notification, in its own {@code REQUIRES_NEW}
     * transaction, with <strong>no outbox row and no retry</strong>: a report abandoned mid-upload
     * stays {@code PENDING_UPLOAD} forever and is invisible in {@code listReports}. It ran on
     * {@code taskExecutor}, whose {@value #SHARED_ASYNC_SECONDS}s slice is explicitly sized for work
     * that is never individually long-running — an S3 round trip routinely is.
     * {@code RadarCompositeCalculationService.onRadarEntrySubmitted} joins it: it takes a pessimistic
     * player-row lock for a read-then-upsert and is the other development-module listener whose task
     * has no fixed bound.
     *
     * <p>8 s buys one full S3 upload plus the short queue behind it. Longer would push the sequential
     * sum past {@code stop_grace_period}; shorter would not cover the case the pool exists for.
     */
    public static final int REPORT_SECONDS = 8;

    /**
     * One in-flight S3 multipart upload part, plus a little of the queue behind it. An abandoned
     * upload is not silently lost data — the caller's transaction has not recorded the object yet —
     * but it does leave an orphaned multipart upload on the bucket for the lifecycle rule to reap.
     */
    public static final int STORAGE_UPLOAD_SECONDS = 5;

    /**
     * The short second wait after {@code shutdownNow()} in {@link #gracefulFixedPool}. Not part of the
     * budget arithmetic above in any meaningful sense: it is only reached once the pool has already
     * blown its whole slice, and it exists to log whether the interrupt actually took, not to give the
     * tasks more time.
     */
    static final int FORCED_TERMINATION_SECONDS = 1;

    private ExecutorShutdown() {
    }

    /**
     * A fixed-size {@link ThreadPoolExecutor} whose {@code shutdown()} also <em>waits</em>, for beans
     * that must expose a plain {@link ExecutorService} and therefore cannot use
     * {@link #configureGracefulShutdown}.
     *
     * <p>{@code shutdown()} blocking is deliberate and is the entire point: Spring's
     * {@code @Bean(destroyMethod = "shutdown")} is its only caller, and the plain
     * {@code ExecutorService.shutdown()} contract ("initiate an orderly shutdown", non-blocking) is
     * exactly what let the JVM exit with work still running. Do not call {@code shutdown()} on one of
     * these from application code.
     *
     * @param awaitSeconds this pool's slice of the budget documented on this class
     */
    public static ExecutorService gracefulFixedPool(int poolSize, int queueCapacity,
                                                    String threadNamePrefix, int awaitSeconds) {
        final AtomicInteger counter = new AtomicInteger();
        return new ThreadPoolExecutor(poolSize, poolSize, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                r -> new Thread(r, threadNamePrefix + counter.incrementAndGet()),
                new ThreadPoolExecutor.CallerRunsPolicy()) {
            @Override
            public void shutdown() {
                super.shutdown();
                try {
                    if (!awaitTermination(awaitSeconds, TimeUnit.SECONDS)) {
                        LoggerFactory.getLogger(ExecutorShutdown.class).warn(
                            "Timed out after {}s waiting for executor '{}' to terminate; {} task(s) "
                                + "were still queued or running -- interrupting them now",
                            awaitSeconds, threadNamePrefix, getQueue().size() + getActiveCount());
                        forceTermination(threadNamePrefix);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    forceTermination(threadNamePrefix);
                }
            }

            /**
             * skillars-deferred-92 code review. The bounded wait above used to be the end of it: on
             * timeout it logged and returned, and on interrupt it returned outright. These are
             * <em>non-daemon</em> workers, so on any context close that is not a JVM exit -- a failed
             * {@code @SpringBootTest} context, a {@code /actuator/restart}, an embedded container
             * shutdown -- they went on running against a torn-down application context, touching a
             * closed datasource and a closed S3 client. Interrupting is the correct end state: the
             * await above has already given every in-flight upload its full slice of the budget, and
             * an upload abandoned here is not lost data (the caller's transaction never recorded the
             * object) -- it is at worst an orphaned multipart upload for the bucket lifecycle rule.
             */
            private void forceTermination(String prefix) {
                shutdownNow();
                try {
                    if (!awaitTermination(FORCED_TERMINATION_SECONDS, TimeUnit.SECONDS)) {
                        LoggerFactory.getLogger(ExecutorShutdown.class).error(
                            "Executor '{}' did not terminate even after shutdownNow(); {} task(s) are "
                                + "ignoring interruption and will outlive the application context",
                            prefix, getQueue().size() + getActiveCount());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
    }

    /**
     * Applies the graceful-shutdown contract. <strong>Call before {@code initialize()} /
     * {@code afterPropertiesSet()}</strong> — see {@code AsyncConfig}'s {@code sendMailPool}, whose
     * {@code setRejectedExecutionHandler} sat after {@code afterPropertiesSet()} and silently had no
     * effect for the life of the bean.
     *
     * @param awaitSeconds this pool's slice of the budget documented on this class
     */
    public static void configureGracefulShutdown(ThreadPoolTaskExecutor executor, int awaitSeconds) {
        // shutdown() rather than shutdownNow(): queued tasks run to completion instead of being
        // discarded, and in-flight tasks are not interrupted mid-write.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitSeconds);
    }
}
