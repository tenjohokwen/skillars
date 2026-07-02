package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.repo.VideoQuotaReservation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaReservationTimeoutService {

    // Safety margin under lockAtMostFor=PT10M: self-terminate the drain loop before ShedLock could
    // force-expire the lock mid-run, which would let a second instance start an overlapping drain.
    // The batch is idempotent (each row is claimed via SKIP LOCKED and only expired once), so bailing
    // out here is safe — the remaining backlog is picked up by the next firing (fixedDelay, default 60s).
    private static final Duration MAX_RUN_DURATION = Duration.ofMinutes(8);

    // Separate bean so Spring proxy intercepts @Transactional — self-invocation bypasses the proxy.
    private final QuotaReservationBatchExpirer batchExpirer;

    @Scheduled(fixedDelayString = "${app.video.reservation-check-interval-ms:60000}")
    @SchedulerLock(name = "QuotaReservationTimeoutService_expire",
                   lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void expireStaleReservations() {
        Instant deadline = Instant.now().plus(MAX_RUN_DURATION);
        int totalExpired = 0;
        List<VideoQuotaReservation> batch;
        // Loop until no more expired rows — drains backlog in a single scheduler firing.
        // Each iteration runs in its own transaction so SKIP LOCKED releases locks promptly.
        do {
            batch = batchExpirer.expireBatch();
            totalExpired += batch.size();
        } while (!batch.isEmpty() && Instant.now().isBefore(deadline));

        if (!batch.isEmpty()) {
            log.warn("Stopped video quota reservation expiry after {} rows — hit the {} safety budget "
                + "under lockAtMostFor=PT10M; remaining backlog will drain on the next scheduled run",
                totalExpired, MAX_RUN_DURATION);
        } else if (totalExpired > 0) {
            log.info("Expired {} stale video quota reservations", totalExpired);
        }
    }
}
