package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.event.VideoStatusChangedEvent;
import com.softropic.skillars.platform.video.contract.exception.TerminalStateViolationException;
import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
import com.softropic.skillars.platform.video.contract.exception.VideoStateConflictException;
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
        // skillars-deferred-100 AC5: PROCESSING→READY removed. Its original producer — the
        // encoding.success webhook firing before Story 6.3 was deployed — is gone
        // (WebhookEventProcessorScheduler records encodingCompletedAt and explicitly does NOT
        // complete transcoding). The only remaining legitimate PROCESSING→READY writes are
        // provider-driven reconciliation corrections, which now go through reconcileToReady().
        OperationalState.PROCESSING,   Set.of(OperationalState.SCANNING, OperationalState.FAILED),
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

        // skillars-deferred-100 AC5: PROCESSING→READY is no longer a valid transition and its only
        // legitimate callers (reconciliation / admin corrections) now use reconcileToReady(). If
        // the plain lifecycle path is ever asked for it again, that is a regression that bypasses
        // the entire moderation pipeline — make it LOUD (ERROR + the bypass counter), not silent,
        // then throw. Kept before the generic validity check purely so the counter still fires.
        if (current == OperationalState.PROCESSING && newState == OperationalState.READY) {
            log.error("PROCESSING→READY requested on the plain lifecycle path for videoId={} — this "
                + "bypasses the moderation pipeline and is no longer a valid transition; "
                + "reconciliation corrections must call reconcileToReady()", videoId);
            meterRegistry.counter("video.moderation.bypass", "from", "PROCESSING", "to", "READY").increment();
            throw new TerminalStateViolationException(videoId, current.name());
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

    /**
     * skillars-deferred-100 AC5: the dedicated path for the only legitimate PROCESSING→READY
     * writes — provider-driven reconciliation corrections ({@code ReconciliationWorkerScheduler},
     * {@code AdminVideoService}) where the provider reports the asset READY but the local row is
     * stuck at PROCESSING. It makes the same state write and fires the same
     * {@link VideoStatusChangedEvent} as {@link #transitionOperationalState} would, but does
     * <strong>not</strong> touch the {@code video.moderation.bypass} counter or log the alarming
     * "moderation pipeline was not run" line — driving these corrections through the plain method
     * (now that PROCESSING→READY is not in {@code VALID_TRANSITIONS}) would be a false positive.
     * The durable record of a legitimate correction is the caller's
     * {@code ReconciliationIncident(STATE_CORRECTED, …)} row; here we only log an INFO tied to
     * {@code reason}.
     *
     * <p>Idempotent when the video is already READY (a prior correction won the race). Any other
     * non-PROCESSING state means the correction no longer applies — a
     * {@link VideoStateConflictException} for the caller, not a 5xx.
     *
     * <p>skillars-deferred-100 code review (2026-09-08): every applied correction increments
     * {@code video.reconciliation.state_corrected}. This path deliberately does not fire
     * {@code video.moderation.bypass}, but a PROCESSING→READY jump still skips the SCANNING
     * moderation step, so it must stay observable — alert on a rate/level here.
     */
    @Observed(name = "video.lifecycle.reconcileToReady")
    @Transactional
    public boolean reconcileToReady(UUID videoId, String reason) {
        Video video = videoRepository.findById(videoId)
            .orElseThrow(() -> new VideoNotFoundException(videoId));

        OperationalState current = video.getOperationalState();
        if (current == OperationalState.READY) {
            return false; // already corrected — idempotent no-op
        }
        if (current != OperationalState.PROCESSING) {
            throw new VideoStateConflictException(videoId, OperationalState.PROCESSING.name(), current.name());
        }

        video.setOperationalState(OperationalState.READY);
        videoRepository.save(video);
        publisher.publishEvent(new VideoStatusChangedEvent(videoId, OperationalState.READY));
        meterRegistry.counter("video.reconciliation.state_corrected").increment();
        log.info("Reconciliation correction PROCESSING→READY for videoId={} reason={}", videoId, reason);
        return true; // state was actually written PROCESSING→READY
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
            // skillars-deferred-91 code review D11: reachable from a request when the video's state
            // changed underneath the caller — a conflict for the client, not a 5xx for alerting.
            throw new VideoStateConflictException(videoId, OperationalState.READY.name(),
                video.getOperationalState().name());
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
