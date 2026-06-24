package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.event.VideoStatusChangedEvent;
import com.softropic.skillars.platform.video.contract.exception.TerminalStateViolationException;
import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoLifecycleService {

    private final VideoRepository videoRepository;
    private final ApplicationEventPublisher publisher;
    private final MeterRegistry meterRegistry;

    private static final Map<OperationalState, Set<OperationalState>> VALID_TRANSITIONS = Map.of(
        OperationalState.UPLOADING,    Set.of(OperationalState.PROCESSING, OperationalState.FAILED),
        OperationalState.PROCESSING,   Set.of(OperationalState.SCANNING, OperationalState.READY, OperationalState.FAILED),
        // PROCESSING→READY kept for backward compat: encoding.success webhook fires before Story 6.3 is deployed.
        // TODO Story 6.5: remove PROCESSING→READY once deployment confirms all PROCESSING→READY events have ceased.
        OperationalState.SCANNING,     Set.of(OperationalState.TRANSCODING, OperationalState.LOCKED, OperationalState.HIDDEN, OperationalState.FAILED),
        OperationalState.TRANSCODING,  Set.of(OperationalState.READY, OperationalState.FAILED),
        OperationalState.FAILED,       Set.of(OperationalState.UPLOADING),
        OperationalState.LOCKED,       Set.of(),  // terminal — admin action required in Story 10
        OperationalState.HIDDEN,       Set.of(OperationalState.TRANSCODING, OperationalState.REJECTED),  // Story 6.6: approval paths
        OperationalState.READY,        Set.of(),
        OperationalState.DELETED,      Set.of()
    );

    @Observed(name = "video.lifecycle.transitionState")
    @Transactional
    public Video transitionOperationalState(UUID videoId, OperationalState newState) {
        Video video = videoRepository.findById(videoId)
            .orElseThrow(() -> new VideoNotFoundException(videoId));

        OperationalState current = video.getOperationalState();

        // Silently discard lifecycle events for purged videos (e.g., encoding webhook firing after user deletion)
        if (current == OperationalState.PURGED) {
            log.warn("[LIFECYCLE_TRANSITION_BLOCKED_PURGED videoId={}]", videoId);
            return video;
        }

        if (current == OperationalState.DELETED) {
            throw new TerminalStateViolationException(videoId, current.name());
        }

        if (current == newState) {
            return video; // idempotent
        }

        // Detect backward-compat bypass BEFORE the validity check so the counter fires even though
        // PROCESSING→READY is a legal transition (kept in VALID_TRANSITIONS until Story 6.5 cleanup).
        if (current == OperationalState.PROCESSING && newState == OperationalState.READY) {
            log.warn("PROCESSING→READY bypass taken for videoId={} — moderation pipeline was not run", videoId);
            meterRegistry.counter("video.moderation.bypass", "from", "PROCESSING", "to", "READY").increment();
        }

        if (!VALID_TRANSITIONS.getOrDefault(current, Set.of()).contains(newState)) {
            throw new TerminalStateViolationException(videoId, current.name());
        }

        if (newState == OperationalState.SCANNING) {
            video.setScanningStartedAt(Instant.now());
        }

        video.setOperationalState(newState);
        Video saved = videoRepository.save(video);

        publisher.publishEvent(new VideoStatusChangedEvent(videoId, newState));

        return saved;
    }

    @Observed(name = "video.lifecycle.setAccessState")
    @Transactional
    public Video setAccessState(UUID videoId, AccessState newAccessState) {
        Video video = videoRepository.findById(videoId)
            .orElseThrow(() -> new VideoNotFoundException(videoId));

        if (video.getOperationalState() == OperationalState.DELETED) {
            throw new TerminalStateViolationException(videoId, OperationalState.DELETED.name());
        }

        video.setAccessState(newAccessState);
        if (newAccessState == AccessState.BLOCKED && video.getLifecycleLockedAt() == null) {
            video.setLifecycleLockedAt(Instant.now());
        }
        return videoRepository.save(video);
    }

    @Observed(name = "video.lifecycle.isPlaybackEligible")
    @Transactional(readOnly = true)
    public boolean isPlaybackEligible(UUID videoId) {
        Video video = videoRepository.findById(videoId)
            .orElseThrow(() -> new VideoNotFoundException(videoId));

        return video.getOperationalState() == OperationalState.READY
            && video.getAccessState() == AccessState.ACTIVE;
    }

    /**
     * Atomically sets accessState=BLOCKED and lifecycleLockedAt in one transaction.
     * Must not be split into two calls — the check constraint fires if lifecycleLockedAt is null on a BLOCKED row.
     */
    @Transactional
    public void blockForSubscriptionExpiry(UUID videoId, Instant lockedAt) {
        Video video = videoRepository.findById(videoId)
            .orElseThrow(() -> new VideoNotFoundException(videoId));
        video.setAccessState(AccessState.BLOCKED);
        video.setLifecycleLockedAt(lockedAt);
        videoRepository.save(video);
    }

    /**
     * Atomically sets accessState=ARCHIVED and archivedAt in one transaction.
     * archivedAt is the Phase 2 reference clock — committed in the same transaction to
     * prevent Phase 2 from selecting a video archived in Phase 1 of the same scheduler run.
     */
    @Transactional
    public void archiveForLifecycle(UUID videoId) {
        Video video = videoRepository.findById(videoId)
            .orElseThrow(() -> new VideoNotFoundException(videoId));
        video.setAccessState(AccessState.ARCHIVED);
        video.setArchivedAt(Instant.now());
        videoRepository.save(video);
    }

    /**
     * Lifecycle escape hatch for READY→DELETED. Does not go through VALID_TRANSITIONS (which blocks READY→*).
     * Sets operationalState=DELETED and storageBytes=0, fires VideoStatusChangedEvent, returns prior storageBytes.
     */
    @Transactional
    public long markPurged(UUID videoId) {
        Video video = videoRepository.findById(videoId)
            .orElseThrow(() -> new VideoNotFoundException(videoId));
        if (video.getOperationalState() != OperationalState.READY) {
            throw new IllegalStateException(
                "markPurged requires operationalState=READY, got " + video.getOperationalState() + " for videoId=" + videoId);
        }
        long priorBytes = video.getStorageBytes() != null ? video.getStorageBytes() : 0L;
        video.setOperationalState(OperationalState.DELETED);
        video.setStorageBytes(0L);
        videoRepository.save(video);
        publisher.publishEvent(new VideoStatusChangedEvent(videoId, OperationalState.DELETED));
        return priorBytes;
    }

    /**
     * Resets lifecycleLockedAt to newClock (yearly expiry clock reset — AC 9 Path A).
     * Does not change accessState — the video remains BLOCKED; the 30-day window simply restarts.
     */
    @Transactional
    public void resetLifecycleClock(UUID videoId, Instant newClock) {
        Video video = videoRepository.findById(videoId)
            .orElseThrow(() -> new VideoNotFoundException(videoId));
        video.setLifecycleLockedAt(newClock);
        videoRepository.save(video);
    }
}
