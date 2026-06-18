package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.config.VideoProperties;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.QuotaProvider;
import com.softropic.skillars.platform.video.contract.UploadSessionStatus;
import com.softropic.skillars.platform.video.repo.UploadSession;
import com.softropic.skillars.platform.video.repo.UploadSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class UploadSessionExpiryScheduler {

    private final UploadSessionRepository uploadSessionRepository;
    private final VideoLifecycleService videoLifecycleService;
    private final QuotaProvider quotaProvider;
    private final VideoProperties properties;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelayString = "${app.video.upload.expiry-scheduler-delay-ms:30000}",
               initialDelayString = "${app.video.upload.expiry-scheduler-delay-ms:30000}")
    public void processExpired() {
        int batchSize = properties.getReconciliation().getBatchSize();

        List<UploadSession> expired = Objects.requireNonNullElse(
            transactionTemplate.execute(status ->
                uploadSessionRepository.findExpiredPendingForUpdate(batchSize)),
            List.of());

        for (UploadSession session : expired) {
            MDC.put("uploadSessionId", session.getId().toString());
            MDC.put("videoId", session.getVideoId().toString());
            try {
                // AC-5: QuotaProvider.release() OUTSIDE any @Transactional boundary
                quotaProvider.release(session.getReservationHandle());
            } catch (Exception e) {
                log.warn("Quota release failed for session {}, retrying next cycle", session.getId(), e);
                MDC.remove("uploadSessionId");
                MDC.remove("videoId");
                continue;
            }
            try {
                transactionTemplate.execute(txStatus -> {
                    UploadSession s = uploadSessionRepository.findById(session.getId()).orElse(null);
                    if (s == null || s.getStatus() != UploadSessionStatus.PENDING) {
                        return null;
                    }
                    s.setStatus(UploadSessionStatus.EXPIRED);
                    uploadSessionRepository.save(s);
                    videoLifecycleService.transitionOperationalState(session.getVideoId(), OperationalState.FAILED);
                    return null;
                });
            } catch (Exception e) {
                log.error("Failed to mark session {} as EXPIRED after quota release", session.getId(), e);
            } finally {
                MDC.remove("uploadSessionId");
                MDC.remove("videoId");
            }
        }
    }
}
