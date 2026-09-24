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
import com.softropic.skillars.platform.payment.contract.event.CoachSubscriptionOrphanedEvent;
import com.softropic.skillars.platform.payment.contract.event.StrikeThresholdReachedEvent;
import com.softropic.skillars.platform.reviews.contract.ReviewFlaggedEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdminAlertEventListener {

    private final AdminAlertRepository adminAlertRepository;
    private final PlatformTransactionManager txManager;

    // skillars-deferred-134 AC1: mirrors GdprErasureService.raiseErasureAlert's own
    // requiresNewTemplate — insertAlert's write must commit (or fail) in its own transaction,
    // isolated from the 5 REQUIRED-propagation callers' own primary business writes. See
    // insertAlert's own Javadoc for why saveAndFlush alone (without this isolation) does not work.
    private TransactionTemplate requiresNewTemplate;

    @PostConstruct
    void initTemplate() {
        requiresNewTemplate = new TransactionTemplate(txManager);
        requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

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

    /**
     * skillars-deferred-133 AC3. {@code REQUIRES_NEW}, unlike {@link #onStrikeThreshold}'s plain
     * {@code @Transactional} above — belt-and-suspenders with {@code StripeWebhookService}'s own
     * wrapping {@code catch (Exception e)} around its resolve-and-publish block (either alone is
     * sufficient to keep {@code handleEventAtomically}'s idempotency-record commit unconditional on
     * this write succeeding; both together is cheap and this path is not connection-pool-constrained
     * the way {@code GdprErasureService}'s is).
     */
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCoachSubscriptionOrphaned(CoachSubscriptionOrphanedEvent event) {
        insertAlert(AdminAlertType.SUBSCRIPTION_ORPHANED,
            event.getCoachProfileId().toString(),
            AdminAlertReferenceType.COACH);
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
        // skillars-deferred-134 AC1: isolated in its own REQUIRES_NEW transaction so a duplicate-alert
        // race (caught below) can never mark the CALLER's own transaction rollback-only. saveAndFlush
        // (not save) forces the INSERT — and any constraint violation — to surface synchronously,
        // inside this REQUIRES_NEW transaction, instead of at the CALLER's own eventual commit;
        // AdminAlert.alertId is GenerationType.UUID, so a plain save() defers the flush past the
        // method body (see GdprErasureService.raiseErasureAlert's Javadoc for the fully-worked-out
        // reasoning this mirrors).
        //
        // The catch is OUTSIDE executeWithoutResult, not inside it (found and corrected during this
        // story's own implementation, via a real concurrency IT — see
        // AdminAlertEventListenerConcurrencyIT's own Javadoc): once saveAndFlush's flush throws, the
        // underlying Hibernate Session is marked for rollback per the JPA spec, REGARDLESS of whether
        // the translated DataIntegrityViolationException is caught in application code — catching it
        // INSIDE the callback and letting the callback return normally does not undo that marking, so
        // TransactionTemplate's own commit() then finds the (new, top-level) transaction rollback-only
        // and throws UnexpectedRollbackException right back out, defeating the whole point of this
        // isolation. Letting the exception propagate OUT of the callback instead makes
        // TransactionTemplate roll back (not commit) this REQUIRES_NEW transaction and re-throw the
        // ORIGINAL DataIntegrityViolationException unchanged, which this method's own try/catch below
        // then catches cleanly, with the caller's transaction never touched either way.
        try {
            requiresNewTemplate.executeWithoutResult(status -> {
                AdminAlert alert = new AdminAlert();
                alert.setType(type);
                alert.setReferenceId(referenceId);
                alert.setReferenceType(referenceType);
                alert.setReason(reason);
                adminAlertRepository.saveAndFlush(alert);
            });
            log.debug("Admin alert created: type={}, referenceId={}", type, referenceId);
        } catch (DataIntegrityViolationException e) {
            // Concurrent insert won the race for the same (referenceId, type) OPEN slot — unique index prevents duplicate.
            log.debug("Admin alert duplicate suppressed by unique index for type={}, referenceId={}", type, referenceId);
        }
    }
}
