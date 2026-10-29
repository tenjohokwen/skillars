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
 * <p>Daily at 06:30 server time, one instance at a time (ShedLock). Reads {@link #BATCH_SIZE} payouts per page,
 * oldest first, paging by keyset through all of them (at most {@link #MAX_PAGES} pages per run).
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
    /** At most this many pages ({@code MAX_PAGES * BATCH_SIZE} payouts) per run. */
    static final int MAX_PAGES = 50;

    private final CoachPayoutRepository coachPayoutRepository;
    private final ConfigService configService;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(cron = "0 30 6 * * *")
    @SchedulerLock(name = "StalePayoutAlertScheduler_alertStalePayouts", lockAtMostFor = "PT15M", lockAtLeastFor = "PT1M")
    public void alertStalePayouts() {
        long days = configService.getBoundedLong(CONFIG_KEY, DEFAULT_DAYS, MIN_DAYS, MAX_DAYS);
        Instant now = Instant.now();
        Instant cutoff = now.minus(Duration.ofDays(days));
        // fix/payment-payout-recovery: keyset pages over (updated_at, booking_id). This job never touches
        // updated_at, so re-reading the first page every run alerted the same oldest BATCH_SIZE rows forever and
        // never reached the rest — exactly the mass-HOLD case. Bounded by MAX_PAGES per run.
        int alerted = 0;
        List<CoachPayout> page = coachPayoutRepository.findStaleUnreleased(cutoff, PageRequest.of(0, BATCH_SIZE));
        for (int pages = 1; !page.isEmpty(); pages++) {
            for (CoachPayout row : page) {
                alert(row, days, now);
                alerted++;
            }
            if (page.size() < BATCH_SIZE || pages >= MAX_PAGES) {
                break;
            }
            CoachPayout last = page.get(page.size() - 1);
            page = coachPayoutRepository.findStaleUnreleasedAfter(cutoff, last.getUpdatedAt(), last.getBookingId(),
                BATCH_SIZE);
        }
        if (alerted > 0) {
            log.warn("[COACH_PAYOUT_STALE] {} payout(s) FROZEN/HOLD unchanged for {}+ days", alerted, days);
        }
    }

    private void alert(CoachPayout row, long days, Instant now) {
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
}
