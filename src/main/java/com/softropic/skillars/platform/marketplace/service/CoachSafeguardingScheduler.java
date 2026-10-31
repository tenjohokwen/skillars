package com.softropic.skillars.platform.marketplace.service;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * S-09: the two daily safeguarding-check jobs. Both are idempotent and ShedLock-guarded, so a second instance (or a
 * rolling deploy) cannot double-run them; the work itself lives in {@link CoachSafeguardingService}.
 */
@Component
@RequiredArgsConstructor
public class CoachSafeguardingScheduler {

    private final CoachSafeguardingService safeguardingService;

    // lockAtMostFor: at most MAX_BATCHES_PER_RUN (50) batches of 100, each upload one outbox enqueue - seconds in
    // practice; 15 minutes is a wide margin.
    @Scheduled(cron = "0 15 3 * * *")
    @SchedulerLock(name = "CoachSafeguardingScheduler_sweepUploads", lockAtMostFor = "PT15M", lockAtLeastFor = "PT1M")
    public void sweepStaleUploads() {
        safeguardingService.sweepStaleUploads();
    }

    @Scheduled(cron = "0 0 9 * * *")
    @SchedulerLock(name = "CoachSafeguardingScheduler_remindExpiring", lockAtMostFor = "PT15M", lockAtLeastFor = "PT1M")
    public void sendExpiryReminders() {
        safeguardingService.sendExpiryReminders();
    }
}
