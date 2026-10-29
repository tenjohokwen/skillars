package com.softropic.skillars.platform.notification.infrastructure.listener;

import com.softropic.skillars.infrastructure.web.FrontendLinkBuilder;
import com.softropic.skillars.platform.admin.contract.AdminNotificationRecipient;
import com.softropic.skillars.platform.admin.contract.CoachReinstatedEvent;
import com.softropic.skillars.platform.admin.contract.CoachSuspensionNotificationEvent;
import com.softropic.skillars.platform.admin.contract.CoachWarningIssuedEvent;
import com.softropic.skillars.platform.admin.contract.DisputeCoachUpheldEvent;
import com.softropic.skillars.platform.admin.contract.DisputeDismissedEvent;
import com.softropic.skillars.platform.admin.contract.DisputeResolvedEvent;
import com.softropic.skillars.platform.admin.contract.GdprExportReadyEvent;
import com.softropic.skillars.platform.admin.contract.UpholdCoachOutcome;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.service.NotificationOutboxSupport;
import com.softropic.skillars.platform.reviews.contract.ReviewModerationResolvedEvent;
import com.softropic.skillars.platform.reviews.contract.ReviewModerationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * B6: tells users the outcome of an admin decision about them — coach suspension / reinstatement /
 * warning, dispute resolved / dismissed, GDPR export ready, review approved / blocked. Each of these
 * events was published with no listener at all, so the affected user was never told.
 *
 * <p>Same shape as {@link BookingEmailListener} (except {@link #onGdprExportReady}, see there):
 * {@code BEFORE_COMMIT}, so the outbox row and the inbox
 * row ({@link NotificationOutboxSupport#enqueueEmail}, {@code MANDATORY}) commit atomically with the
 * admin decision — a rolled-back decision sends nothing, a committed one cannot lose its email. Holds no
 * repository: the publishing admin service resolves the recipient (a minor player's parent instead of
 * the child) and every display fact inside its own transaction and puts them on the event. A null
 * recipient (no reachable address) is logged and skipped.
 *
 * <p>All nine templates are transactional (not opt-out-able) — see {@code NotificationCatalog}.
 * {@code CoachSuspendedEvent} is deliberately <em>not</em> consumed: it is always published, including
 * for a child-safety hold and when the admin chose not to notify; {@link CoachSuspensionNotificationEvent}
 * is the one that carries the admin's "notify coach" choice.
 *
 * <p>Template data is string-typed (amounts and instants formatted here), per
 * {@code EmailDataRoundTripContractTest}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminOutcomeEmailListener {

    private final NotificationOutboxSupport notificationOutboxSupport;
    private final FrontendLinkBuilder frontendLinks;
    private final PlatformTransactionManager transactionManager;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onCoachSuspensionNotification(CoachSuspensionNotificationEvent event) {
        Map<String, Object> data = new HashMap<>();
        data.put("coachDisplayName", text(event.getCoachDisplayName()));
        data.put("reason", text(event.getReason()));
        data.put("profileUrl", frontendLinks.route("profile"));
        send(EmailTemplate.COACH_SUSPENDED, event.getRecipient(), data, "coachId", event.getCoachId());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onCoachReinstated(CoachReinstatedEvent event) {
        Map<String, Object> data = new HashMap<>();
        data.put("coachDisplayName", text(event.getCoachDisplayName()));
        data.put("dashboardUrl", frontendLinks.route("dashboard"));
        send(EmailTemplate.COACH_REINSTATED, event.getRecipient(), data, "coachId", event.getCoachId());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onCoachWarningIssued(CoachWarningIssuedEvent event) {
        Map<String, Object> data = new HashMap<>();
        data.put("disputeReason", text(event.getReason()));
        data.put("reliabilityUrl", frontendLinks.route("coach/reliability"));
        send(EmailTemplate.COACH_WARNING_ISSUED, event.getRecipient(), data, "disputeId", event.getDisputeId());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onDisputeResolved(DisputeResolvedEvent event) {
        Map<String, Object> data = new HashMap<>();
        data.put("resolution", text(event.getResolution()));
        data.put("creditAmount", amount(event.getCreditAmount()));
        data.put("resolutionNote", text(event.getResolutionNote()));
        data.put("coachDisplayName", text(event.getCoachDisplayName()));
        data.put("requestedStartTime", instant(event.getSessionStart()));
        putUpholdOutcome(data, event.getUpholdOutcome(), event.getAudience());
        send(EmailTemplate.DISPUTE_RESOLVED, event.getRecipient(), data, "disputeId", event.getDisputeId());
    }

    /** feat/payment-forfeit-payouts-and-clawback: the party that did not raise an upheld-coach dispute. */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onDisputeCoachUpheld(DisputeCoachUpheldEvent event) {
        Map<String, Object> data = new HashMap<>();
        data.put("coachDisplayName", text(event.coachDisplayName()));
        data.put("requestedStartTime", instant(event.sessionStart()));
        putUpholdOutcome(data, event.outcome(), event.audience());
        send(EmailTemplate.DISPUTE_COACH_UPHELD, event.recipient(), data, "disputeId", event.disputeId());
    }

    /** UPHOLD_COACH facts, string-typed; nothing is added for any other resolution. */
    private static void putUpholdOutcome(Map<String, Object> data, UpholdCoachOutcome outcome,
                                         UpholdCoachOutcome.Audience audience) {
        if (outcome == null) {
            return;
        }
        data.put("audience", audience == null ? "" : audience.name());
        data.put("refundedAmount", amount(outcome.refunded()));
        data.put("recoveredAmount", amount(outcome.recovered()));
        data.put("absorbedAmount", amount(outcome.absorbed()));
        data.put("coachPayoutAmount", amount(outcome.coachNet()));
        data.put("packSession", String.valueOf(outcome.packSession()));
        data.put("sessionRededucted", String.valueOf(outcome.sessionRededucted()));
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onDisputeDismissed(DisputeDismissedEvent event) {
        Map<String, Object> data = new HashMap<>();
        data.put("reason", text(event.reason()));
        data.put("coachDisplayName", text(event.coachDisplayName()));
        data.put("requestedStartTime", instant(event.sessionStart()));
        send(EmailTemplate.DISPUTE_DISMISSED, event.recipient(), data, "disputeId", event.disputeId());
    }

    /**
     * The one AFTER_COMMIT handler, in its own REQUIRES_NEW transaction (which satisfies
     * {@code enqueueEmail}'s MANDATORY). The export build is the user's legal right; its notice is not.
     * Joining the build's transaction would let an enqueue failure mark it rollback-only after
     * {@code buildExport}'s own catch-to-FAILED block has passed — the export would roll back to
     * PENDING forever with its ZIP orphaned. The price: a crash between the export commit and this
     * enqueue loses the notice; the profile page still shows the export as ready.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGdprExportReady(GdprExportReadyEvent event) {
        Map<String, Object> data = new HashMap<>();
        // The signed download URL is deliberately NOT emailed: the user downloads it from their own
        // logged-in profile page, so a forwarded or leaked email does not hand out their data.
        data.put("expiresAt", instant(event.getExpiresAt()));
        data.put("profileUrl", frontendLinks.route("profile"));
        try {
            // A failed enqueue rolls back only this transaction; the commit's exception is caught here so
            // nothing propagates out of the after-commit callback.
            requiresNew().executeWithoutResult(status ->
                send(EmailTemplate.GDPR_EXPORT_READY, event.getRecipient(), data, "requestId", event.getRequestId()));
        } catch (RuntimeException e) {
            log.error("Failed to enqueue GDPR export-ready notice", kv("requestId", event.getRequestId()), e);
        }
    }

    private TransactionTemplate requiresNew() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onReviewModerationResolved(ReviewModerationResolvedEvent event) {
        EmailTemplate template;
        if (event.newStatus() == ReviewModerationStatus.APPROVED) {
            template = EmailTemplate.REVIEW_APPROVED;
        } else if (event.newStatus() == ReviewModerationStatus.BLOCKED) {
            template = EmailTemplate.REVIEW_BLOCKED;
        } else {
            return;
        }
        AdminNotificationRecipient recipient = event.authorEmail() == null || event.authorEmail().isBlank()
            ? null : new AdminNotificationRecipient(event.authorEmail(), event.authorLangKey());
        Map<String, Object> data = new HashMap<>();
        data.put("coachDisplayName", text(event.coachDisplayName()));
        data.put("coachProfileUrl", frontendLinks.route("coaches/" + event.coachId()));
        send(template, recipient, data, "reviewId", event.reviewId());
    }

    private void send(EmailTemplate template, AdminNotificationRecipient target, Map<String, Object> data,
                      String refName, Object refId) {
        try {
            if (target == null || target.email() == null || target.email().isBlank()) {
                log.warn("Admin outcome email skipped: no reachable recipient", kv("template", template),
                    kv(refName, refId));
                return;
            }
            Recipient recipient = new Recipient();
            recipient.setEmail(target.email());
            recipient.setLangKey(target.langKey() == null ? "en" : target.langKey());
            notificationOutboxSupport.enqueueEmail(template, recipient, data, UUID.randomUUID().toString());
        } catch (Exception e) {
            // Same contract as BookingEmailListener: enqueueEmail is MANDATORY, so a failure there has
            // already marked the admin decision's transaction rollback-only — this catch only logs it.
            log.error("Failed to prepare/publish notification", kv("template", template), kv(refName, refId), e);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String instant(Instant value) {
        return value == null ? "" : value.toString();
    }

    private static String amount(BigDecimal value) {
        return value == null || value.signum() <= 0 ? "" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
