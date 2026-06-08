package com.softropic.skillars.platform.tenant.service;

import static net.logstash.logback.argument.StructuredArguments.kv;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RotatedKeyCleanupJob extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(RotatedKeyCleanupJob.class);

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private ObservationRegistry observationRegistry;

    /**
     * Quartz entry point. @Observed cannot advise this protected method via Spring AOP
     * (the parent class calls it via self-invocation, bypassing the proxy), so we use
     * a programmatic Observation that produces the same span + timer as @Observed would.
     */
    @Override
    @Transactional
    protected void executeInternal(JobExecutionContext context) {
        Observation.createNotStarted("quartz.rotated-key-cleanup", observationRegistry)
                .lowCardinalityKeyValue("job", "RotatedKeyCleanupJob")
                .observe(this::runCleanup);
    }

    private void runCleanup() {
        int revokedCount = apiKeyService.revokeExpiredRotatedKeys();
        if (revokedCount > 0) {
            log.info("Rotated key cleanup complete",
                kv("operation", "rotated_key_cleanup"),
                kv("revokedCount", revokedCount));
        }
    }
}
