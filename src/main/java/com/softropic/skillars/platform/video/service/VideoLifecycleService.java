package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.exception.TerminalStateViolationException;
import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoLifecycleService {

    private final VideoRepository videoRepository;

    private static final Map<OperationalState, Set<OperationalState>> VALID_TRANSITIONS = Map.of(
        OperationalState.UPLOADING, Set.of(OperationalState.PROCESSING, OperationalState.FAILED),
        OperationalState.PROCESSING, Set.of(OperationalState.READY, OperationalState.FAILED),
        OperationalState.FAILED, Set.of(OperationalState.UPLOADING),
        OperationalState.READY, Set.of(),
        OperationalState.DELETED, Set.of()
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
            return video; // idempotent — preserves scheduler behavior for FAILED→FAILED race
        }

        if (!VALID_TRANSITIONS.getOrDefault(current, Set.of()).contains(newState)) {
            throw new TerminalStateViolationException(videoId, current.name());
        }

        video.setOperationalState(newState);
        return videoRepository.save(video);
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
