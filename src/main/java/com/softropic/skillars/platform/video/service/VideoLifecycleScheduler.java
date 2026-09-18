package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.video.contract.LifecycleTrigger;
import com.softropic.skillars.platform.video.contract.PlayerSubscriptionQueryPort;
import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
import com.softropic.skillars.platform.video.contract.exception.VideoProviderException;
import com.softropic.skillars.platform.video.contract.exception.VideoStateConflictException;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoLifecycleLog;
import com.softropic.skillars.platform.video.repo.VideoLifecycleLogRepository;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;


@Slf4j
@Component
@RequiredArgsConstructor
public class VideoLifecycleScheduler {

    private final VideoRepository videoRepository;
    private final VideoLifecycleLogRepository videoLifecycleLogRepository;
    private final VideoLifecycleService videoLifecycleService;
    private final VideoProviderAdapter videoProviderAdapter;
    private final ConfigService configService;
    private final TransactionTemplate transactionTemplate;
    private final QuotaService quotaService;
    private final PlayerSubscriptionQueryPort playerSubscriptionQueryPort;

    // skillars-deferred-115 code review: extracted so the phase name used in logging can't drift
    // between the three call sites (log.debug/log.info/logSkippedVideo) that each spell it out.
    private static final String PHASE_BLOCKED_TO_ARCHIVED = "BLOCKED→ARCHIVED";
    private static final String PHASE_ARCHIVED_TO_DELETED = "ARCHIVED→DELETED";

    /**
     * skillars-deferred-115 AC2 sizing basis for the annotation below: {@code batch_size} is
     * operator-configurable up to a ceiling of {@code 10000} ({@code getBoundedInt(..., 100, 1,
     * 10000)} below) — 10000 rows per phase, times 2 phases, each row making one external provider
     * HTTP call ({@code archiveAsset}/{@code deleteAsset}, via {@code VideoProviderConfig}'s
     * {@code RestTemplate}: 10s connect / 30s read timeout) plus one short DB write. A successful
     * call is sub-second in practice; a run dominated by actual timeouts would itself mean the
     * provider dependency is down — no {@code lockAtMostFor} value fixes that, it needs operator
     * intervention regardless. Explicit sizing arithmetic (code review 2026-09-16):
     * {@code batch_size(10000) × phases(2) = 20000} per-row operations; at the expected sub-second
     * per-row latency that is on the order of minutes, at the 30s-read-timeout worst case that is
     * {@code 20000 × 30s = 600000s} (~166h) if literally every call timed out — an already-broken
     * provider dependency, not a scenario {@code lockAtMostFor} is meant to cover. {@code PT12H} sits
     * well above the expected-latency case with real margin, while staying far short of the
     * all-timeouts pathological case that needs operator intervention regardless of this value.
     * Sized off the CEILING (not the default of 100) so raising
     * {@code platform.video.lifecycle.batch_size} does not silently make this too tight: a run at
     * the configured ceiling is expected to take on the order of hours, and {@code lockAtMostFor}
     * is set generously above that so the lock cannot expire out from under a still-running,
     * legitimate ceiling-sized batch — the failure mode if it did (a second instance starting
     * mid-run) is a genuine double-archive/double-delete attempt, not just a missed tick. Re-tune
     * if {@code batch_size}'s ceiling itself is ever raised. {@code lockAtLeastFor} is a small
     * clock-skew guard for the shared once-daily cron tick — unlike {@code EmailRetryScheduler}'s
     * {@code fixedDelay} job, there is no back-to-back-tick throughput concern for a job that only
     * fires once a day.
     */
    // skillars-deferred-123 AC4: both floors property-ized (this scheduler's cadence,
    // app.video.lifecycle.cron, is also operator-tunable) — see OutboxService.sweep's identical
    // comment for the rationale.
    @SchedulerLock(name = "VideoLifecycleScheduler_runLifecycleJob",
                   lockAtMostFor = "${app.video.lifecycle.lock-at-most:PT12H}",
                   lockAtLeastFor = "${app.video.lifecycle.lock-at-least:PT30S}")
    @Scheduled(cron = "${app.video.lifecycle.cron:0 0 3 * * *}")
    public void runLifecycleJob() {
        // skillars-deferred-107 AC2: 0/neg would archive/delete immediately (or never); 0 batch size
        // stalls the scheduler. Mirrors ConfigBounds.VIDEO_LIFECYCLE_*.
        long blockedToArchivedDays = configService.getBoundedLong(
            "platform.video.lifecycle.blocked_to_archived_days", 30L, 1L, 3650L);
        // Ceiling 36500 (100y), not 3650: physical deletion + the 4-arg overload falls back to the
        // 90-day default on out-of-range, so a low ceiling would silently delete years early
        // (skillars-deferred-107 code review). Mirrors ConfigBounds.VIDEO_LIFECYCLE_ARCHIVED_TO_DELETED_DAYS.
        long archivedToDeletedDays = configService.getBoundedLong(
            "platform.video.lifecycle.archived_to_deleted_days", 90L, 1L, 36500L);
        int batchSize = configService.getBoundedInt("platform.video.lifecycle.batch_size", 100, 1, 10000);

        int archivedCount = runBlockedToArchivedPhase(blockedToArchivedDays, batchSize);
        int deletedCount  = runArchivedToDeletedPhase(archivedToDeletedDays, batchSize);

        log.info("VideoLifecycleScheduler completed: archived={} deleted={}", archivedCount, deletedCount);
    }

    private int runBlockedToArchivedPhase(long blockedToArchivedDays, int batchSize) {
        Instant threshold = Instant.now().minus(blockedToArchivedDays, ChronoUnit.DAYS);
        List<Video> candidates = videoRepository.findBlockedExceedingThreshold(threshold, batchSize);
        int count = 0;
        for (Video video : candidates) {
            try {
                Long playerId = Long.parseLong(video.getOwnerId());
                if (playerSubscriptionQueryPort.hasActiveYearlySubscription(playerId)) {
                    log.debug("Yearly exemption: skipping {} for videoId={} ownerId={}",
                        PHASE_BLOCKED_TO_ARCHIVED, video.getId(), video.getOwnerId());
                    continue;
                }
            } catch (NumberFormatException e) {
                log.warn("Cannot parse ownerId as Long for yearly check, proceeding: videoId={} ownerId={}", video.getId(), video.getOwnerId());
            }

            // External Bunny call outside @Transactional — 404 treated as success (idempotency)
            try {
                videoProviderAdapter.archiveAsset(video.getProviderAssetId());
            } catch (VideoProviderException e) {
                log.error("archiveAsset failed for videoId={}: {}", video.getId(), e.getMessage());
                continue;
            }

            UUID videoId = video.getId();
            // skillars-deferred-115 AC2: the state-transition write used to be unguarded — one
            // video's ObjectOptimisticLockingFailureException (a genuinely concurrent writer) or any
            // other RuntimeException propagated out of this loop, silently dropping every remaining
            // candidate in this phase AND skipping runArchivedToDeletedPhase entirely. Mirrors
            // ReconciliationWorkerScheduler.sweepOrphanedProviderAssets()'s broad-catch breadth: a
            // narrow catch would silently regress to that same abort-the-batch bug the first time an
            // unlisted exception type appears.
            try {
                transactionTemplate.execute(ignored -> {
                    videoLifecycleService.archiveForLifecycle(videoId);
                    videoLifecycleLogRepository.save(buildLog(videoId, "BLOCKED", "ARCHIVED", LifecycleTrigger.SYSTEM));
                    return null;
                });
                count++;
            } catch (RuntimeException e) {
                logSkippedVideo(videoId, PHASE_BLOCKED_TO_ARCHIVED, e);
            }
        }
        log.info("Phase {}: processed={}", PHASE_BLOCKED_TO_ARCHIVED, count);
        return count;
    }

    private int runArchivedToDeletedPhase(long archivedToDeletedDays, int batchSize) {
        Instant threshold = Instant.now().minus(archivedToDeletedDays, ChronoUnit.DAYS);
        List<Video> candidates = videoRepository.findArchivedExceedingThreshold(threshold, batchSize);
        int count = 0;
        for (Video video : candidates) {
            // External Bunny call outside @Transactional — 404 treated as success (idempotency)
            try {
                videoProviderAdapter.deleteAsset(video.getProviderAssetId());
            } catch (VideoProviderException e) {
                log.error("deleteAsset failed for videoId={}: {}", video.getId(), e.getMessage());
                continue;
            }

            UUID videoId = video.getId();
            // skillars-deferred-115 AC2: see the matching comment in runBlockedToArchivedPhase().
            try {
                transactionTemplate.execute(ignored -> {
                    Video v = videoRepository.findById(videoId).orElseThrow();
                    long released = videoLifecycleService.markPurged(v.getId());
                    quotaService.decrementStorageBytes(v.getOwnerId(), released);
                    videoLifecycleLogRepository.save(buildLog(v.getId(), "ARCHIVED", "DELETED", LifecycleTrigger.SYSTEM));
                    return null;
                });
                count++;
            } catch (RuntimeException e) {
                logSkippedVideo(videoId, PHASE_ARCHIVED_TO_DELETED, e);
            }
        }
        log.info("Phase {}: processed={}", PHASE_ARCHIVED_TO_DELETED, count);
        return count;
    }

    /**
     * skillars-deferred-115 AC2: one log line per skipped video (id + exception type/message) so a
     * partially-failed batch is visible even though nothing aborts it. {@link VideoNotFoundException}
     * and {@link VideoStateConflictException} are {@code markPurged}/{@code archiveForLifecycle}'s
     * only explicitly-thrown types; {@link ObjectOptimisticLockingFailureException} arises implicitly
     * from {@code @Version} on a genuinely concurrent write — all three are expected, recoverable
     * per-video conditions and log at WARN. Anything else is unanticipated and logs at ERROR (with
     * the stack trace) so it stays loud even though it no longer aborts the batch.
     */
    private void logSkippedVideo(UUID videoId, String phase, RuntimeException e) {
        if (e instanceof ObjectOptimisticLockingFailureException
            || e instanceof VideoNotFoundException
            || e instanceof VideoStateConflictException) {
            log.warn("Skipping video {} in {} phase — {}: {}",
                videoId, phase, e.getClass().getSimpleName(), e.getMessage());
        } else {
            log.error("Skipping video {} in {} phase — unexpected {}: {}",
                videoId, phase, e.getClass().getSimpleName(), e.getMessage(), e);
        }
    }

    private VideoLifecycleLog buildLog(UUID videoId, String from, String to, String triggeredBy) {
        VideoLifecycleLog log = new VideoLifecycleLog();
        log.setVideoId(videoId);
        log.setFromState(from);
        log.setToState(to);
        log.setTriggeredBy(triggeredBy);
        return log;
    }
}
