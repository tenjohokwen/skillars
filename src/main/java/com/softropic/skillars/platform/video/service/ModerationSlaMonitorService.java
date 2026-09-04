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
import java.util.List;

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
                    continue;
                }
                exhausted++;
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
            }
        }
        if (retried > 0)
            log.info("Requeued {} videos stuck in SCANNING beyond {}min SLA", retried, slaMinutes);
        if (exhausted > 0)
            log.error("Permanently failed {} videos after exhausting {} moderation retries", exhausted, maxRetries);
    }
}
