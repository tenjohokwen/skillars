package com.softropic.skillars.platform.notification.config;




import com.softropic.skillars.infrastructure.threadpool.MdcDecorator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tag;

@Configuration
@EnableAsync
@EnableScheduling
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
        taskExecutor.afterPropertiesSet();
        taskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
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
