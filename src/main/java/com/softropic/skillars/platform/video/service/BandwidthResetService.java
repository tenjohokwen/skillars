package com.softropic.skillars.platform.video.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BandwidthResetService {

    private final JdbcTemplate jdbcTemplate;

    @Scheduled(cron = "0 0 0 1 * ?", zone = "UTC")
    @SchedulerLock(name = "BandwidthResetService_reset",
                   lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void resetMonthlyBandwidth() {
        // Idempotency: only reset rows where bandwidth_period_start is in a prior month
        int updated = jdbcTemplate.update("""
            UPDATE main.video_quotas
            SET bandwidth_used_bytes = 0,
                bandwidth_period_start = NOW()
            WHERE DATE_TRUNC('month', bandwidth_period_start) < DATE_TRUNC('month', NOW())
            """);
        log.info("Monthly bandwidth reset applied to {} video quota rows", updated);
    }
}
