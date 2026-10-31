package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.platform.admin.contract.AdminAlertReferenceType;
import com.softropic.skillars.platform.admin.contract.AdminAlertType;
import com.softropic.skillars.platform.admin.repo.AdminAlert;
import com.softropic.skillars.platform.admin.repo.AdminAlertRepository;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByParentEvent;
import com.softropic.skillars.platform.booking.contract.CoachNoShowEvent;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * S-07 (owner decision 2026-10-31): an INFORMATIONAL alert when one payer keeps claiming that coaches did not show.
 * Nothing is blocked, refused or decided automatically and there is nothing for an admin to adjudicate — it is a
 * heads-up, marked handled like any payment-integrity alert.
 *
 * <p>A claim is any of the three doors that stamp {@code bookings.coach_no_show_claimed_at} (V218): a report on an
 * UPCOMING session, a late parent cancel after start + grace, and a conflict-zone split. When the payer's number of
 * claims within {@code booking.coachNoShow.repeatClaimAlertWindowDays} (default 90) reaches
 * {@code booking.coachNoShow.repeatClaimAlertThreshold} (default 3), one {@code REPEAT_NO_SHOW_CLAIMANT} alert is
 * raised for that payer for the window — "one per payer per window" holds even after an admin handled the first.
 *
 * <p>AFTER_COMMIT + REQUIRES_NEW, like the other alert listeners: the claim has committed (so it is counted), and a
 * failure here can never undo a refund. Concurrent claims by one payer are serialised by a transaction-scoped
 * advisory lock around the count-then-insert.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoachNoShowRepeatClaimantAlertListener {

    private final BookingRepository bookingRepository;
    private final AdminAlertRepository adminAlertRepository;
    private final ConfigService configService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCoachNoShow(CoachNoShowEvent event) {
        check(event.getParentId(), event.getBookingId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBookingCancelledByParent(BookingCancelledByParentEvent event) {
        if (event.isCoachNoShow()) {
            check(event.getParentId(), event.getBookingId());
        }
    }

    private void check(Long payerId, UUID bookingId) {
        try {
            long threshold = configService.getBoundedLong(ConfigBounds.BOOKING_COACH_NO_SHOW_REPEAT_CLAIM_ALERT_THRESHOLD);
            long windowDays = configService.getBoundedLong(ConfigBounds.BOOKING_COACH_NO_SHOW_REPEAT_CLAIM_ALERT_WINDOW_DAYS);
            Instant since = Instant.now().minus(windowDays, ChronoUnit.DAYS);
            adminAlertRepository.acquireRepeatClaimantLock("payer:" + payerId);
            long claims = bookingRepository.countByParentIdAndCoachNoShowClaimedAtAfter(payerId, since);
            if (claims < threshold) {
                return;
            }
            if (adminAlertRepository.countRepeatClaimantAlertsSince("payer " + payerId + ":%", since) > 0) {
                return;
            }
            AdminAlert alert = new AdminAlert();
            alert.setType(AdminAlertType.REPEAT_NO_SHOW_CLAIMANT);
            alert.setReferenceId(bookingId.toString());
            alert.setReferenceType(AdminAlertReferenceType.BOOKING);
            alert.setReason("payer " + payerId + ": " + claims + " claims in " + windowDays + "d");
            adminAlertRepository.save(alert);
            log.warn("[REPEAT_NO_SHOW_CLAIMANT] payer {} has {} coach no-show claims in {} days — informational alert raised",
                payerId, claims, windowDays);
        } catch (RuntimeException e) {
            // Informational only: never let it surface into the committed claim.
            log.error("Repeat no-show claimant check failed for payer {} booking {}", payerId, bookingId, e);
        }
    }
}
