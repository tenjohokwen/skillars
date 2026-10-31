package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.platform.admin.contract.AdminAlertReferenceType;
import com.softropic.skillars.platform.admin.contract.AdminAlertStatus;
import com.softropic.skillars.platform.admin.contract.AdminAlertType;
import com.softropic.skillars.platform.admin.contract.DisputeRaisedEvent;
import com.softropic.skillars.platform.admin.repo.AdminAlert;
import com.softropic.skillars.platform.admin.repo.AdminAlertRepository;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByAdminEvent;
import com.softropic.skillars.platform.messaging.contract.ConversationReportedEvent;
import com.softropic.skillars.platform.messaging.contract.MessageHeldForReviewEvent;
import com.softropic.skillars.platform.messaging.contract.ModerationFailureEvent;
import com.softropic.skillars.platform.messaging.contract.MessageReportedEvent;
import com.softropic.skillars.platform.messaging.contract.MessagesPurgedEvent;
import com.softropic.skillars.platform.payment.contract.event.CashOutRefundResolvedEvent;
import com.softropic.skillars.platform.payment.contract.event.CashOutRefundUnresolvedEvent;
import com.softropic.skillars.platform.payment.contract.event.PaymentIntegrityAlertEvent;
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
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdminAlertEventListener {

    /** admin_alerts.reason value for a hold caused by a failed moderation call (fits varchar(64)). */
    static final String REASON_PROVIDER_FAILED = "MODERATION_PROVIDER_FAILED";

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

    /**
     * B6, issue 2: {@code GeminiModerationService} publishes {@link ModerationFailureEvent} when the
     * classifier call itself failed (outage, timeout, bad response). The message was already held
     * UNDER_REVIEW and {@link #onMessageHeldForReview} already raised the MODERATION_UNRESOLVED alert — but
     * with the verdict-level reason MODERATION_UNCERTAIN, which tells the reviewer "the classifier was
     * unsure", when nothing assessed the content at all. This re-stamps that open alert's reason as
     * {@value #REASON_PROVIDER_FAILED} so the queue summary leads with the real cause. It does not create
     * an alert when none is open: the hold path only skips the alert when the message was already
     * resolved or deleted, and an alert for that would be noise.
     *
     * <p>Plain {@code @EventListener}: the publisher holds no transaction. The update runs in its own
     * REQUIRES_NEW and every failure is caught here — the publisher does not wrap this publish, and the
     * moderation verdict it follows has already committed; a lost reason re-stamp must not fail it.
     */
    @EventListener
    public void onModerationFailure(ModerationFailureEvent event) {
        if (event.messageId() == null) {
            return;
        }
        try {
            Integer updated = requiresNewTemplate.execute(status -> adminAlertRepository.updateOpenAlertReason(
                String.valueOf(event.messageId()), AdminAlertType.MODERATION_UNRESOLVED.name(),
                REASON_PROVIDER_FAILED));
            log.warn("[MODERATION_PROVIDER_FAILED] messageId={} conversationId={} openAlertsMarked={}",
                event.messageId(), event.conversationId(), updated);
        } catch (RuntimeException e) {
            log.error("Failed to mark MODERATION_UNRESOLVED alert as a provider failure for message {}",
                event.messageId(), e);
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

    /**
     * feat/coach-payouts-pack-and-credit (C3 item 4): a credit cash-out refund Stripe never confirmed —
     * the payer's wallet is debited and the money may or may not have reached their card. Same isolation
     * as {@link #onCoachSubscriptionOrphaned}: the publisher's (CashOutReconciliationService) transaction
     * is never affected by this insert. AFTER_COMMIT: the alert exists only once the UNKNOWN status it
     * describes has committed.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCashOutRefundUnresolved(CashOutRefundUnresolvedEvent event) {
        insertAlert(AdminAlertType.CASH_OUT_UNRESOLVED,
            event.getCashOutId().toString(),
            AdminAlertReferenceType.CASH_OUT,
            truncateReason(event.getReason()));
    }

    /**
     * feat/coach-payouts-pack-and-credit: the cash-out settled itself (refund confirmed, or rejected and
     * credit restored) — close its open alert so no admin re-credits or re-refunds it by hand.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCashOutRefundResolved(CashOutRefundResolvedEvent event) {
        adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(
                event.getCashOutId().toString(), AdminAlertType.CASH_OUT_UNRESOLVED, AdminAlertStatus.OPEN)
            .ifPresent(alert -> {
                alert.setStatus(AdminAlertStatus.RESOLVED);
                alert.setResolvedAt(java.time.Instant.now());
                adminAlertRepository.save(alert);
                // fix/audit3-wallet-cashout-integrity: the leg note ties a split cash-out's legs together.
                log.info("CASH_OUT_UNRESOLVED alert for cash-out {} resolved automatically ({}){}",
                    event.getCashOutId(), event.getOutcome(),
                    event.getLegNote() == null ? "" : " — " + event.getLegNote());
            });
    }

    /**
     * fix/payment-payouts-disputes-webhooks: chargebacks, refunds made outside the app, held payouts and
     * failed reversals. AFTER_COMMIT (fallbackExecution: an outbox handler or webhook always has a
     * transaction, but never lose the alert if one does not) + REQUIRES_NEW, like
     * {@link #onCashOutRefundUnresolved}. One OPEN alert per (reference, type): a later event — a
     * chargeback closing, a payout held again — refreshes the open alert's reason instead.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPaymentIntegrityAlert(PaymentIntegrityAlertEvent event) {
        AdminAlertType type = AdminAlertType.valueOf(event.getKind().name());
        AdminAlertReferenceType referenceType = switch (event.getReference()) {
            case BOOKING -> AdminAlertReferenceType.BOOKING;
            case PAYMENT_INTENT -> AdminAlertReferenceType.PAYMENT_INTENT;
            case COACH -> AdminAlertReferenceType.COACH;
        };
        String reason = truncateReason(event.getReason());
        if (adminAlertRepository.updateOpenAlertReason(event.getReferenceId(), type.name(), reason) > 0) {
            return;
        }
        insertAlert(type, event.getReferenceId(), referenceType, reason);
    }

    /**
     * S-07: a session nobody started is now refunded automatically (BookingCancelledByAdminEvent with reason
     * NEVER_STARTED). A SESSION_NEVER_STARTED alert raised for it before the change has nothing left to decide, so it is
     * closed with the refund (resolved_by stays null: no admin made the decision). A no-op when none is open.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onNeverStartedSessionAutoRefunded(BookingCancelledByAdminEvent event) {
        if (!BookingCancelledByAdminEvent.REASON_NEVER_STARTED.equals(event.getCancelReason())) {
            return;
        }
        adminAlertRepository.resolveOpenAlertsForReference(
            event.getBookingId().toString(), AdminAlertType.SESSION_NEVER_STARTED.name(), null);
    }

    private static String truncateReason(String reason) {
        return reason != null && reason.length() > 64 ? reason.substring(0, 64) : reason;
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
