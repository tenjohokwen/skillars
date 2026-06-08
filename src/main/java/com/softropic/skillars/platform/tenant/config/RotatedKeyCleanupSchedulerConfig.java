package com.softropic.skillars.platform.tenant.config;

import com.softropic.skillars.platform.tenant.service.RotatedKeyCleanupJob;

import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RotatedKeyCleanupSchedulerConfig {

    @Bean
    public JobDetail rotatedKeyCleanupJobDetail() {
        return JobBuilder.newJob(RotatedKeyCleanupJob.class)
            .withIdentity("rotatedKeyCleanupJob")
            .storeDurably()
            .build();
    }

    @Bean
    public Trigger rotatedKeyCleanupTrigger(JobDetail rotatedKeyCleanupJobDetail) {
        return TriggerBuilder.newTrigger()
            .forJob(rotatedKeyCleanupJobDetail)
            .withIdentity("rotatedKeyCleanupTrigger")
            .withSchedule(SimpleScheduleBuilder.repeatMinutelyForever(5))
            .build();
    }
}
