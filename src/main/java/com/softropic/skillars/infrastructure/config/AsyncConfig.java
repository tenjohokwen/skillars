package com.softropic.skillars.infrastructure.config;

import com.softropic.skillars.infrastructure.threadpool.ExecutorShutdown;
import com.softropic.skillars.infrastructure.threadpool.MdcDecorator;

import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * General-purpose async executor configuration.
 *
 * <p>This class is distinct from {@code com.softropic.skillars.platform.notification.config.AsyncConfig},
 * which owns the email send pool ({@code "sendMailPool"} bean). Both classes co-exist
 * in different packages and declare different bean names — no conflict.
 *
 * <p>{@code @EnableAsync} is intentionally omitted: the email {@code AsyncConfig}
 * already activates it project-wide. Adding it again here would be harmless but redundant.
 *
 * <p>{@link MdcDecorator} propagates MDC so every {@code @Async} task keeps the
 * logging context of the originating request. This used to be composed with a
 * TenantContextTaskDecorator; that was removed along with the tenant module, since
 * nothing populates a tenant identifier any more.
 */
@Configuration("skillarsAsyncConfig")
public class AsyncConfig implements AsyncConfigurer {

    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("skillars-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        executor.setTaskDecorator(task -> new MdcDecorator().decorate(task));
        // skillars-deferred-92 AC3. Short, but not zero: this is the pool every bare @Async in the
        // application resolves to (see AC17 / DefaultAsyncExecutorResolutionTest), so whatever lands
        // here inherits this shutdown behaviour. See ExecutorShutdown for the budget.
        ExecutorShutdown.configureGracefulShutdown(executor, ExecutorShutdown.SHARED_ASYNC_SECONDS);

        executor.initialize();
        return executor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
            LoggerFactory.getLogger(AsyncConfig.class)
                .error("Uncaught exception in @Async method '{}': {}", method.getName(), ex.getMessage(), ex);
    }
}
