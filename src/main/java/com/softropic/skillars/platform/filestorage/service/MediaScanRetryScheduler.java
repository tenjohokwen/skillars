package com.softropic.skillars.platform.filestorage.service;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * S-18: retries images the abuse-media scanner could not judge yet (it was down, timed out, or the upload raced a
 * restart). Without this an image would stay hidden forever after one scanner outage. Each scan call is bounded by
 * the Arachnid read timeout (30 s) and the batch by {@code infrastructure.arachnid.sweep-batch-size} (20), so the
 * worst case is 20 x 30 s = 10 min of a single tick; lockAtMostFor PT15M covers that with margin.
 */
@Component
@RequiredArgsConstructor
public class MediaScanRetryScheduler {

    private final MediaScanService mediaScanService;

    @Scheduled(fixedDelayString = "${infrastructure.arachnid.sweep-delay-ms:60000}")
    @SchedulerLock(name = "MediaScanRetryScheduler_retryPending", lockAtMostFor = "PT15M", lockAtLeastFor = "PT30S")
    public void retryPending() {
        mediaScanService.retryPending();
    }
}
