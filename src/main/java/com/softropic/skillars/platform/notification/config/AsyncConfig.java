package com.softropic.skillars.platform.notification.config;




import com.softropic.skillars.infrastructure.threadpool.ExecutorShutdown;
import com.softropic.skillars.infrastructure.threadpool.MdcDecorator;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tag;

@Configuration
@EnableAsync
// @EnableScheduling moved to infrastructure.config.SchedulingConfig so it can be switched off
// under the test profile. @EnableSchedulerLock deliberately stays here and stays unconditional:
// tests invoke scheduled methods directly through the Spring proxy, so the lock advisor must
// still apply (see BasePaymentIT.releaseSchedulerLock).
// order: ShedLock's default InterceptMode.PROXY_METHOD wraps @SchedulerLock methods with a genuine
// AOP advisor on the same proxy chain as @Transactional (see DataSourceConfig's @EnableTransactionManagement,
// also un-ordered). Both default to Ordered.LOWEST_PRECEDENCE, so without an explicit order their relative
// nesting is unspecified. Setting a lower (higher-precedence) order here forces the lock advisor outermost,
// so proceed() always runs the transaction to completion (commit/rollback) before the lock is released —
// the lock can never be freed while the DB transaction is still open.
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M", order = Ordered.LOWEST_PRECEDENCE - 100)
public class AsyncConfig {
    @Bean(name = "moderationTaskExecutor")
    public Executor moderationTaskExecutor() {
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(2);
        taskExecutor.setQueueCapacity(200);
        taskExecutor.setMaxPoolSize(5);
        taskExecutor.setThreadNamePrefix("modPool");
        taskExecutor.setTaskDecorator(new MdcDecorator());
        taskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // skillars-deferred-92 AC3. Note the ordering here — every setter BEFORE afterPropertiesSet().
        // This bean was always correct; sendMailPool below was not. See its comment.
        ExecutorShutdown.configureGracefulShutdown(taskExecutor, ExecutorShutdown.MODERATION_SECONDS);
        taskExecutor.afterPropertiesSet();
        return taskExecutor;
    }

    @Bean(name = "sendMailPool")
    public Executor threadPoolTaskExecutor() {
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(3);
        taskExecutor.setQueueCapacity(10);
        taskExecutor.setMaxPoolSize(10);
        taskExecutor.setThreadNamePrefix("smPool");
        taskExecutor.setTaskDecorator(new MdcDecorator());
        // skillars-deferred-92 AC3.3 — BUG FIX, not a reshuffle. setRejectedExecutionHandler used to
        // sit AFTER afterPropertiesSet(). ExecutorConfigurationSupport.afterPropertiesSet() calls
        // initialize(), which builds the underlying ThreadPoolExecutor from the fields set so far; a
        // later setter mutates the Spring wrapper's field and never reaches the live executor. So this
        // pool ran with the JDK default AbortPolicy for the whole life of the bean: a full 10-deep
        // queue threw RejectedExecutionException at the caller instead of sending the mail inline,
        // which is the exact opposite of what CallerRunsPolicy was chosen for.
        // moderationTaskExecutor directly above always ordered these two correctly.
        // ExecutorShutdownConfigurationTest asserts the live executor's handler, not this field.
        taskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        ExecutorShutdown.configureGracefulShutdown(taskExecutor, ExecutorShutdown.SEND_MAIL_SECONDS);
        taskExecutor.afterPropertiesSet();
        //Tags are also referred to as labels or dimensions, depending upon which Application Performance Monitoring (APM) tool is being utilized
        final Tag tag = Tag.of("name", "sendmailPool");
        final Set<Tag> tags = Set.of(tag);
        //TODO Does this work as expected? Compare with using  `taskExecutor.setTaskDecorator()`
        Metrics.gaugeCollectionSize("sendmail.queue.size", tags, taskExecutor.getThreadPoolExecutor().getQueue());
        Metrics.gauge("sendmail.pool.size", tags, taskExecutor, ThreadPoolTaskExecutor::getPoolSize);
        Metrics.gauge("sendmail.active.thread.count", tags, taskExecutor, ThreadPoolTaskExecutor::getActiveCount);
        return taskExecutor;
    }

}
