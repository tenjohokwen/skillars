package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.platform.admin.contract.AdminAlertReferenceType;
import com.softropic.skillars.platform.admin.contract.AdminAlertStatus;
import com.softropic.skillars.platform.admin.contract.AdminAlertType;
import com.softropic.skillars.platform.admin.repo.AdminAlert;
import com.softropic.skillars.platform.admin.repo.AdminAlertRepository;
import com.softropic.skillars.platform.messaging.contract.ConversationReportedEvent;
import com.softropic.skillars.platform.messaging.contract.MessageReportedEvent;
import com.softropic.skillars.platform.payment.contract.event.StrikeThresholdReachedEvent;
import com.softropic.skillars.platform.reviews.contract.ReviewFlaggedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
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

    private void insertAlert(AdminAlertType type, String referenceId, AdminAlertReferenceType referenceType) {
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
            adminAlertRepository.save(alert);
            log.debug("Admin alert created: type={}, referenceId={}", type, referenceId);
        } catch (DataIntegrityViolationException e) {
            // Concurrent insert won the race for the same (referenceId, type) OPEN slot — unique index prevents duplicate.
            log.debug("Admin alert duplicate suppressed by unique index for type={}, referenceId={}", type, referenceId);
        }
    }
}
