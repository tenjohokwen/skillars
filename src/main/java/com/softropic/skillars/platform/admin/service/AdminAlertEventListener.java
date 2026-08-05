package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.platform.admin.contract.AdminAlertReferenceType;
import com.softropic.skillars.platform.admin.contract.AdminAlertStatus;
import com.softropic.skillars.platform.admin.contract.AdminAlertType;
import com.softropic.skillars.platform.admin.contract.DisputeRaisedEvent;
import com.softropic.skillars.platform.admin.repo.AdminAlert;
import com.softropic.skillars.platform.admin.repo.AdminAlertRepository;
import com.softropic.skillars.platform.messaging.contract.ConversationReportedEvent;
import com.softropic.skillars.platform.messaging.contract.MessageHeldForReviewEvent;
import com.softropic.skillars.platform.messaging.contract.MessageReportedEvent;
import com.softropic.skillars.platform.messaging.contract.MessagesPurgedEvent;
import com.softropic.skillars.platform.payment.contract.event.StrikeThresholdReachedEvent;
import com.softropic.skillars.platform.reviews.contract.ReviewFlaggedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdminAlertEventListener {

    private final AdminAlertRepository adminAlertRepository;

    @EventListener
    @Transactional
    public void onMessageReported(MessageReportedEvent event) {
        insertAlert(AdminAlertType.MESSAGE_REPORT,
            String.valueOf(event.messageId()),
            AdminAlertReferenceType.MESSAGE);
    }

    @EventListener
    @Transactional
    public void onConversationReported(ConversationReportedEvent event) {
        insertAlert(AdminAlertType.CONVERSATION_REPORT,
            String.valueOf(event.conversationId()),
            AdminAlertReferenceType.CONVERSATION);
    }

    /**
     * REQUIRES_NEW, unlike every other handler here, and deliberately so — this one is published
     * from inside {@code ModerationResultApplier.applyResult} and {@code MessageModerationSweeper},
     * where a shared transaction would let a failed alert insert roll back the moderation verdict
     * itself and return the message to PENDING: exactly the stranding those classes exist to fix.
     * Its own transaction commits (or fails) inside {@code publishEvent}, where both publishers
     * catch and log. Note a try/catch in this method body would not work — {@code AdminAlert.alertId}
     * is {@code GenerationType.UUID}, so the INSERT is deferred to flush at commit, after the body
     * has returned.
     */
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onMessageHeldForReview(MessageHeldForReviewEvent event) {
        insertAlert(AdminAlertType.MODERATION_UNRESOLVED,
            String.valueOf(event.messageId()),
            AdminAlertReferenceType.MESSAGE,
            event.reason());
    }

    /**
     * Closes alerts left pointing at messages the retention scheduler just hard-deleted. Runs
     * REQUIRES_NEW for the same reason as {@link #onMessageHeldForReview}: a cleanup failure must
     * not roll back the retention run that published it.
     */
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onMessagesPurged(MessagesPurgedEvent event) {
        int resolved = adminAlertRepository.resolveOpenAlertsForDeletedMessages();
        if (resolved > 0) {
            log.info("Resolved {} admin alert(s) referencing messages removed by retention "
                + "({} messages purged)", resolved, event.deletedCount());
        }
    }

    @EventListener
    @Transactional
    public void onReviewFlagged(ReviewFlaggedEvent event) {
        insertAlert(AdminAlertType.REVIEW_FLAG,
            event.reviewId().toString(),
            AdminAlertReferenceType.REVIEW);
    }

    @EventListener
    @Transactional
    public void onStrikeThreshold(StrikeThresholdReachedEvent event) {
        insertAlert(AdminAlertType.STRIKE_THRESHOLD,
            event.getCoachId().toString(),
            AdminAlertReferenceType.COACH);
    }

    @EventListener
    @Transactional
    public void onDisputeRaised(DisputeRaisedEvent event) {
        insertAlert(AdminAlertType.DISPUTE_RAISED,
            event.getBookingId().toString(),
            AdminAlertReferenceType.BOOKING);
    }

    private void insertAlert(AdminAlertType type, String referenceId, AdminAlertReferenceType referenceType) {
        insertAlert(type, referenceId, referenceType, null);
    }

    private void insertAlert(AdminAlertType type, String referenceId,
                             AdminAlertReferenceType referenceType, String reason) {
        if (adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(
                referenceId, type, AdminAlertStatus.OPEN).isPresent()) {
            log.debug("Admin alert already OPEN for type={}, referenceId={} — skipping duplicate", type, referenceId);
            return;
        }
        try {
            AdminAlert alert = new AdminAlert();
            alert.setType(type);
            alert.setReferenceId(referenceId);
            alert.setReferenceType(referenceType);
            alert.setReason(reason);
            adminAlertRepository.save(alert);
            log.debug("Admin alert created: type={}, referenceId={}", type, referenceId);
        } catch (DataIntegrityViolationException e) {
            // Concurrent insert won the race for the same (referenceId, type) OPEN slot — unique index prevents duplicate.
            log.debug("Admin alert duplicate suppressed by unique index for type={}, referenceId={}", type, referenceId);
        }
    }
}
