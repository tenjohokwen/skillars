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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    // REQUIRES_NEW so each per-video TX is isolated from the outer @Transactional that holds
    // the PESSIMISTIC_WRITE row locks; prevents the inner TXs from joining the outer TX and
    // rolling back the entire batch on any single failure.
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

    @Observed(name = "video.moderation.slaMonitor")
    @Transactional
    @Scheduled(fixedDelayString = "${app.video.moderation.sla-monitor-delay-ms:300000}")
    public void detectSlaViolations() {
        long slaMinutes = configService.getLong("platform.moderation_sla_minutes");
        long maxRetries = configService.getLong("platform.moderation_max_retries");
        Instant threshold = Instant.now().minus(slaMinutes, ChronoUnit.MINUTES);

        List<Video> stuckVideos = videoRepository.findScanningOlderThan(threshold, Instant.now(), 50);
        int retried = 0, exhausted = 0;
        for (Video video : stuckVideos) {
            try {
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
