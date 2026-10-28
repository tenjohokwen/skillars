package com.softropic.skillars.platform.notification.service;

import com.softropic.skillars.platform.notification.repo.UserNotificationRepository;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;

/**
 * Deletes in-app notifications older than the retention window (default 90 days). Copies
 * {@code MessageRetentionScheduler}'s shape: daily cron, ShedLock so only one instance runs, each
 * batch in its own short transaction so a large backlog never holds one long lock.
 */
@Slf4j
@Component
public class InAppNotificationRetentionScheduler {

    static final int BATCH_SIZE = 1000;
    /** Floor on the configured window: a 0/negative value would otherwise delete the whole inbox. */
    static final int MIN_RETENTION_DAYS = 7;
    /** Upper bound on batches per run, so a misconfiguration cannot spin for the whole lock window. */
    static final int MAX_BATCHES_PER_RUN = 500;

    private final UserNotificationRepository notificationRepository;
    private final TransactionTemplate transactionTemplate;
    private final int retentionDays;

    public InAppNotificationRetentionScheduler(
            final UserNotificationRepository notificationRepository,
            final TransactionTemplate transactionTemplate,
            @Value("${platform.notification.in-app-retention-days:90}") final int retentionDays) {
        this.notificationRepository = notificationRepository;
        this.transactionTemplate = transactionTemplate;
        if (retentionDays < MIN_RETENTION_DAYS) {
            log.warn("platform.notification.in-app-retention-days={} is below the {}-day floor; clamping",
                retentionDays, MIN_RETENTION_DAYS);
        }
        this.retentionDays = Math.max(MIN_RETENTION_DAYS, retentionDays);
    }

    // lockAtMostFor derivation: at most MAX_BATCHES_PER_RUN (500) batches of BATCH_SIZE (1000) rows,
    // each an index-driven DELETE of ~1000 rows (well under 1s) → worst case a few minutes, so 30
    // minutes leaves a wide margin. Revisit if either constant grows. Concurrent deletes (GDPR
    // erasure, a second instance after lock expiry) are harmless: DELETE of an already-deleted id
    // matches no row.
    @Scheduled(cron = "0 30 3 * * *")
    @SchedulerLock(name = "InAppNotificationRetentionScheduler_purge",
                   lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void purge() {
        final Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        int total = 0;
        try {
            for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
                final Integer deleted = transactionTemplate.execute(
                    status -> notificationRepository.deleteCreatedBefore(cutoff, BATCH_SIZE));
                final int n = deleted == null ? 0 : deleted;
                total += n;
                if (n < BATCH_SIZE) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.error("In-app notification retention sweep failed after deleting {} rows", total, e);
            return;
        }
        log.info("In-app notification retention run complete: deleted {} rows older than {} days",
            total, retentionDays);
    }
}
