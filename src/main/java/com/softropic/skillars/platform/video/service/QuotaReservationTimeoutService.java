package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.repo.VideoQuotaReservation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaReservationTimeoutService {

    // Separate bean so Spring proxy intercepts @Transactional — self-invocation bypasses the proxy.
    private final QuotaReservationBatchExpirer batchExpirer;

    @Scheduled(fixedDelayString = "${app.video.reservation-check-interval-ms:60000}")
    public void expireStaleReservations() {
        int totalExpired = 0;
        List<VideoQuotaReservation> batch;
        // Loop until no more expired rows — drains backlog in a single scheduler firing.
        // Each iteration runs in its own transaction so SKIP LOCKED releases locks promptly.
        do {
            batch = batchExpirer.expireBatch();
            totalExpired += batch.size();
        } while (!batch.isEmpty());

        if (totalExpired > 0) {
            log.info("Expired {} stale video quota reservations", totalExpired);
        }
    }
}
