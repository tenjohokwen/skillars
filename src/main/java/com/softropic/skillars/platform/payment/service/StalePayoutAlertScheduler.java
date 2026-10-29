package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.payment.contract.event.PaymentIntegrityAlertEvent;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
import com.softropic.skillars.platform.payment.repo.CoachPayoutRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * fix/payment-dispute-credit-trace-and-lost-dispute: the safety net for coach payouts no automatic path
 * will release. A payout in {@code FROZEN} or {@code HOLD} that has not changed for
 * {@code payment.payout.stale_alert_days} (default 14) raises a {@code PAYOUT_HELD} admin alert on its
 * booking. The alert queue keeps one OPEN alert per booking and type, so a payout still stuck on the next
 * run only refreshes the reason. Read-only: it never moves a payout.
 *
 * <p>Daily at 06:30 server time, one instance at a time (ShedLock). At most {@link #BATCH_SIZE} payouts per
 * run, oldest first; the rest are picked up on later runs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StalePayoutAlertScheduler {

    static final String CONFIG_KEY = "payment.payout.stale_alert_days";
    static final long DEFAULT_DAYS = 14L;
    static final long MIN_DAYS = 1L;
    static final long MAX_DAYS = 365L;
    static final int BATCH_SIZE = 200;

    private final CoachPayoutRepository coachPayoutRepository;
    private final ConfigService configService;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(cron = "0 30 6 * * *")
    @SchedulerLock(name = "StalePayoutAlertScheduler_alertStalePayouts", lockAtMostFor = "PT15M", lockAtLeastFor = "PT1M")
    public void alertStalePayouts() {
        long days = configService.getBoundedLong(CONFIG_KEY, DEFAULT_DAYS, MIN_DAYS, MAX_DAYS);
        Instant now = Instant.now();
        List<CoachPayout> stale = coachPayoutRepository.findStaleUnreleased(now.minus(Duration.ofDays(days)),
            PageRequest.of(0, BATCH_SIZE));
        for (CoachPayout row : stale) {
            long idleDays = row.getUpdatedAt() == null ? days : Duration.between(row.getUpdatedAt(), now).toDays();
            String reason = "STALE_" + row.getStatus() + " " + idleDays + "d"
                + (row.getLastError() != null ? " " + row.getLastError() : "");
            log.warn("[COACH_PAYOUT_STALE] booking={} coach={} status={} net={} unchanged for {}d (last_error={}) — "
                + "no automatic release, alerting", row.getBookingId(), row.getCoachId(), row.getStatus(),
                row.getNetAmount(), idleDays, row.getLastError());
            try {
                eventPublisher.publishEvent(new PaymentIntegrityAlertEvent(this, PaymentIntegrityAlertEvent.Kind.PAYOUT_HELD,
                    row.getBookingId().toString(), PaymentIntegrityAlertEvent.Reference.BOOKING, reason));
            } catch (RuntimeException e) {
                // One alert failing must not stop the others; it is retried on the next run.
                log.error("[COACH_PAYOUT_STALE] could not raise the alert for booking {}", row.getBookingId(), e);
            }
        }
        if (!stale.isEmpty()) {
            log.warn("[COACH_PAYOUT_STALE] {} payout(s) FROZEN/HOLD unchanged for {}+ days", stale.size(), days);
        }
    }
}
