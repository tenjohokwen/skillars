package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.exception.TerminalStateViolationException;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import io.micrometer.observation.annotation.Observed;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class ModerationSlaMonitorService {

    private final VideoRepository videoRepository;
    private final VideoLifecycleService videoLifecycleService;
    private final ConfigService configService;
    private final ModerationOutboxSupport moderationOutboxSupport;
    private final TransactionTemplate transactionTemplate;
    private final PlatformTransactionManager txManager;

    // REQUIRES_NEW so each per-video write is its own transaction, isolated from every other
    // per-video write in this run: one video's failure (or rollback) cannot take another's
    // already-committed work down with it. skillars-deferred-115 AC1: this class used to also rely
    // on REQUIRES_NEW to isolate these writes from a method-level @Transactional that held the
    // batch's PESSIMISTIC_WRITE locks for the whole run — that outer transaction is gone (see
    // detectSlaViolations()'s javadoc), so REQUIRES_NEW's remaining job is purely per-video isolation.
    private TransactionTemplate requiresNewTemplate;

    // skillars-deferred-93 P13: track consecutive per-video processing failures; if a video
    // fails repeatedly across cycles, force it to FAILED (with admin alert) so an infinite-loop
    // exception cannot escape notice.
    private final Map<UUID, Integer> videoConsecutiveFailures = new HashMap<>();
    private static final int CONSECUTIVE_FAILURE_THRESHOLD = 3;

    @PostConstruct
    void initTemplates() {
        requiresNewTemplate = new TransactionTemplate(txManager);
        requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * skillars-deferred-115 AC1: this method used to carry its own method-level {@code @Transactional},
     * wrapping the whole body — {@code findScanningOlderThan}'s {@code SELECT ... FOR UPDATE SKIP LOCKED}
     * AND the entire per-video loop below — so all ≤{@code moderation_sla_batch_size} selected rows
     * stayed row-locked, and their entities stayed persistence-context-managed, for the full method
     * duration, not just the read.
     *
     * <p>Confirmed against a real Postgres 16 container: this was not just lock contention against
     * <em>other</em> writers, it could self-block. Every {@code requiresNewTemplate.execute(...)} call
     * below eventually writes to the SAME {@code Video} row the (former) outer transaction held under
     * {@code FOR UPDATE} — so the outer connection sat parked inside
     * {@code requiresNewTemplate.execute(...)}, waiting for the inner {@code REQUIRES_NEW} write, which
     * itself waited on the outer's still-held row lock. A same-thread, cross-connection circular wait
     * Postgres's own deadlock detector cannot see (it only observes the inner side as blocked — the
     * outer connection is idle from the engine's perspective, not itself waiting on a DB-visible lock).
     *
     * <p>Fix (mirrors {@code ReconciliationWorkerScheduler}/{@code WebhookEventProcessorScheduler}):
     * the batch load below runs inside its own short-lived {@link #transactionTemplate} transaction, so
     * the SELECT's {@code FOR UPDATE} locks release as soon as the load returns — before the per-video
     * loop, and every {@code requiresNewTemplate.execute(...)} inside it, even begins.
     *
     * <p><strong>Double-pick decision:</strong> with the batch lock no longer held for the whole method,
     * {@code SKIP LOCKED} means a second concurrent run (another pod, or an overlapping tick) could
     * select the same video before this run's per-video write commits. No explicit "claim" step
     * (a {@code PENDING}→{@code PROCESSING}-style write, as {@code WebhookEventProcessorScheduler} uses)
     * was added — same reasoning {@code ReconciliationWorkerScheduler.reconcile()} already relies on:
     * {@code Video} carries {@code @Version}, so a genuinely concurrent double-write on the
     * retry-count-increment path throws {@link org.springframework.orm.ObjectOptimisticLockingFailureException},
     * caught by this method's own per-video {@code catch (Exception e)} below and treated exactly like
     * any other transient per-video failure — no state corruption, and no more disruptive than the
     * pre-existing consecutive-failure bookkeeping already tolerates. The FAILED-transition branch is
     * idempotent on a re-run that lands after the winner's commit
     * ({@code VideoLifecycleService.transitionOperationalState} treats {@code FAILED}→{@code FAILED} as
     * a no-op), so its only observable double-pick cost is a duplicate admin-alert enqueue — an
     * operational nuisance, not a correctness or data-loss issue. Both outcomes are bounded to the rare
     * window where two runs' batch loads overlap on the identical stuck video, matching the tradeoff
     * this module already accepts elsewhere.
     */
    @Observed(name = "video.moderation.slaMonitor")
    @Scheduled(fixedDelayString = "${app.video.moderation.sla-monitor-delay-ms:300000}")
    public void detectSlaViolations() {
        // skillars-deferred-107 AC2: 0/neg SLA → every SCANNING video is instantly breached and
        // re-queued (failFast). maxRetries floor is 0 ("no retries" is legitimate); neg would invert
        // the retry-count comparison. Mirrors ConfigBounds.MODERATION_SLA_MINUTES / MODERATION_MAX_RETRIES.
        long slaMinutes = configService.getBoundedLong("platform.moderation_sla_minutes", 1L, 10080L);
        long maxRetries = configService.getBoundedLong("platform.moderation_max_retries", 0L, 100L);
        // skillars-deferred-115 AC1: was a hardcoded literal (50) at this call site — externalized so
        // an operator can tune it without a deploy, mirroring every sibling scheduler's config-bound
        // batch size (e.g. platform.video.lifecycle.batch_size).
        int batchSize = configService.getBoundedInt("platform.moderation_sla_batch_size", 50, 1, 500);
        Instant threshold = Instant.now().minus(slaMinutes, ChronoUnit.MINUTES);

        List<Video> stuckVideos = Objects.requireNonNullElse(
            transactionTemplate.execute(status ->
                videoRepository.findScanningOlderThan(threshold, Instant.now(), batchSize)),
            List.of());
        int retried = 0, exhausted = 0;
        for (Video video : stuckVideos) {
            try {
                // skillars-deferred-115 code review 2026-09-16: this branch decision reads
                // moderationRetryCount from the batch-load snapshot, which — now that AC1 shortened
                // the load's own transaction — a genuinely concurrent writer could have advanced past
                // by the time this iteration runs (the old method-level @Transactional held every
                // selected row's lock for the whole sweep, so this decision could never be stale
                // before). Accepted, not fixed: the persisted increment below always re-reads fresh
                // (never double-counts or loses an increment), so the only possible outcome is one
                // extra retry dispatched and moderationRetryCount overshooting maxRetries by at most 1
                // — self-corrected on the very next cycle (5min default), the same "stale
                // batch-snapshot decision, downstream re-validates" tradeoff
                // ReconciliationWorkerScheduler.processReconciliation already makes on its own stale
                // video.getOperationalState() read.
                if (video.getModerationRetryCount() >= maxRetries) {
                    log.error("Moderation max retries ({}) exceeded for videoId={} — transitioning to FAILED",
                              maxRetries, video.getId());
                    try {
                        // skillars-deferred-92 AC5: the alert is enqueued INSIDE the same REQUIRES_NEW
                        // transaction as the FAILED transition, so the two are atomic. Previously the
                        // transition committed and the alert was published afterwards from a bare
                        // ApplicationEventPublisher — a crash in between marked a video permanently failed
                        // with nothing telling a human it needed manual review, and the next SLA cycle
                        // would not re-select it because it had left SCANNING.
                        requiresNewTemplate.execute(status -> {
                            videoLifecycleService.transitionOperationalState(video.getId(), OperationalState.FAILED);
                            moderationOutboxSupport.enqueueAdminAlert(
                                video.getId(), video.getOwnerId(),
                                "Moderation pipeline permanently failed",
                                "videoId=" + video.getId() + " retries=" + video.getModerationRetryCount()
                                    + " — manual review required", true);
                            return null;
                        });
                    } catch (TerminalStateViolationException e) {
                        log.warn("SLA FAILED transition skipped — videoId={} already in terminal state", video.getId());
                        videoConsecutiveFailures.remove(video.getId());
                        continue;
                    }
                    exhausted++;
                    videoConsecutiveFailures.remove(video.getId());
                } else {
                    // Increment retry count before dispatching to prevent concurrent SLA cycles from
                    // re-queuing the same video when the lock just expired
                    final long newRetryCount = video.getModerationRetryCount() + 1;
                    // skillars-deferred-92 AC5: increment AND enqueue in one transaction. They were
                    // separate — the counter committed in its own REQUIRES_NEW and the retry was then
                    // published from a bare ApplicationEventPublisher — so a crash in between burned one
                    // of the video's finite retry attempts on a retry that was never requested.
                    // VideoModerationRetryEvent (not VideoUploadedEvent) skips the PROCESSING→SCANNING
                    // transition; ModerationRetryOutboxHandler re-publishes it after the drain.
                    requiresNewTemplate.execute(status -> {
                        videoRepository.findById(video.getId()).ifPresent(v -> {
                            v.setModerationRetryCount(v.getModerationRetryCount() + 1);
                            videoRepository.save(v);
                        });
                        moderationOutboxSupport.enqueueRetry(video.getId(), video.getOwnerId());
                        return null;
                    });
                    log.warn("Moderation SLA exceeded for videoId={} stuck since={} retry={}/{}",
                             video.getId(), video.getScanningStartedAt(),
                             newRetryCount, maxRetries);
                    retried++;
                    videoConsecutiveFailures.remove(video.getId());
                }
            } catch (Exception e) {
                log.error("Failed to process SLA violation for videoId={}", video.getId(), e);
                // skillars-deferred-93 P13: track consecutive failures and force FAILED if threshold exceeded.
                // A persistently-throwing REQUIRES_NEW block (a stuck resource lock, a crashing
                // downstream system, etc.) would otherwise loop forever unnoticed. The threshold is
                // low (3) because the SLA monitor runs frequently (5min default); three consecutive
                // failures = 15 minutes of unrecoverable failure.
                int failureCount = videoConsecutiveFailures.getOrDefault(video.getId(), 0) + 1;
                videoConsecutiveFailures.put(video.getId(), failureCount);

                if (failureCount >= CONSECUTIVE_FAILURE_THRESHOLD) {
                    log.error("Moderation SLA processing for videoId={} failed {} times — forcing FAILED",
                              video.getId(), failureCount);
                    try {
                        requiresNewTemplate.execute(status -> {
                            videoLifecycleService.transitionOperationalState(video.getId(), OperationalState.FAILED);
                            moderationOutboxSupport.enqueueAdminAlert(
                                video.getId(), video.getOwnerId(),
                                "Moderation pipeline permanently failed",
                                "videoId=" + video.getId() + " — SLA processing failed " + failureCount
                                    + " consecutive times; manual review required", true);
                            return null;
                        });
                    } catch (TerminalStateViolationException ex) {
                        log.warn("SLA FAILED transition skipped — videoId={} already in terminal state", video.getId());
                    }
                    videoConsecutiveFailures.remove(video.getId());
                    exhausted++;
                }
            }
        }
        if (retried > 0)
            log.info("Requeued {} videos stuck in SCANNING beyond {}min SLA", retried, slaMinutes);
        if (exhausted > 0)
            log.error("Permanently failed {} videos after exhausting {} moderation retries or {} consecutive failures", exhausted, maxRetries, CONSECUTIVE_FAILURE_THRESHOLD);
    }
}
