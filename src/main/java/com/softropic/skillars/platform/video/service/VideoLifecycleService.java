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
        OperationalState.HIDDEN,       Set.of(),  // terminal in Story 6.3 ONLY — Story 6.6 MUST add HIDDEN → {TRANSCODING, FAILED, DELETED}
        OperationalState.READY,        Set.of(),
        OperationalState.DELETED,      Set.of()
    );

    @Observed(name = "video.lifecycle.transitionState")
    @Transactional
    public Video transitionOperationalState(UUID videoId, OperationalState newState) {
        Video video = videoRepository.findById(videoId)
            .orElseThrow(() -> new VideoNotFoundException(videoId));

        OperationalState current = video.getOperationalState();

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
}
