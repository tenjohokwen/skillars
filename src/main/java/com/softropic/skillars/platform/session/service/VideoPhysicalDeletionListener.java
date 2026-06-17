package com.softropic.skillars.platform.session.service;

import com.softropic.skillars.platform.session.contract.VideoPhysicalDeletionEvent;
import com.softropic.skillars.platform.video.service.AdminVideoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@Slf4j
@RequiredArgsConstructor
public class VideoPhysicalDeletionListener {

    private final AdminVideoService adminVideoService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVideoPhysicalDeletion(VideoPhysicalDeletionEvent event) {
        try {
            adminVideoService.deleteVideo(event.videoId());
        } catch (Exception e) {
            log.error("Failed to physically delete video {} for drill {}", event.videoId(), event.drillId(), e);
        }
    }
}
