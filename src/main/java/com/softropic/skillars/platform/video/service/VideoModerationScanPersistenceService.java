package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.repo.VideoModerationScanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// REQUIRES_NEW ensures this write always runs in its own TX regardless of the caller's TX state.
// A DataIntegrityViolationException from a concurrent retry race on UNIQUE(video_id, layer) is
// isolated to this TX and never marks the caller's outer TX as rollback-only.
@Service
@Slf4j
@RequiredArgsConstructor
public class VideoModerationScanPersistenceService {

    private final VideoModerationScanRepository moderationScanRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsertScanRecord(UUID videoId, String layer, String outcome,
                                 Double confidence, String details) {
        moderationScanRepository.upsertScan(videoId, layer, outcome, confidence, details);
    }
}
