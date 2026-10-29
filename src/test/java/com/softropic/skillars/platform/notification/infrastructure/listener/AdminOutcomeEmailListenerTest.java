package com.softropic.skillars.platform.notification.infrastructure.listener;

import com.softropic.skillars.infrastructure.web.FrontendLinkBuilder;
import com.softropic.skillars.platform.admin.contract.AdminNotificationRecipient;
import com.softropic.skillars.platform.admin.contract.CoachReinstatedEvent;
import com.softropic.skillars.platform.admin.contract.CoachSuspensionNotificationEvent;
import com.softropic.skillars.platform.admin.contract.CoachWarningIssuedEvent;
import com.softropic.skillars.platform.admin.contract.DisputeDismissedEvent;
import com.softropic.skillars.platform.admin.contract.DisputeResolvedEvent;
import com.softropic.skillars.platform.admin.contract.GdprExportReadyEvent;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.service.NotificationOutboxSupport;
import com.softropic.skillars.platform.reviews.contract.ReviewModerationResolvedEvent;
import com.softropic.skillars.platform.reviews.contract.ReviewModerationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** B6: every admin-outcome event reaches the outbox with the right template, address and string-typed data. */
class AdminOutcomeEmailListenerTest {

    private static final AdminNotificationRecipient TO = new AdminNotificationRecipient("to@x.test", "de");

    private NotificationOutboxSupport outbox;
    private AdminOutcomeEmailListener listener;

    @BeforeEach
    void setUp() {
        outbox = mock(NotificationOutboxSupport.class);
        listener = new AdminOutcomeEmailListener(outbox, new FrontendLinkBuilder("https://app.test"),
            mock(org.springframework.transaction.PlatformTransactionManager.class));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sent(EmailTemplate template) {
        ArgumentCaptor<Recipient> recipient = ArgumentCaptor.forClass(Recipient.class);
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(outbox).enqueueEmail(eq(template), recipient.capture(), data.capture(), anyString());
        assertThat(recipient.getValue().getEmail()).isEqualTo("to@x.test");
        assertThat(recipient.getValue().getLangKey()).isEqualTo("de");
        assertThat(data.getValue().values()).allMatch(v -> v instanceof String);
        return data.getValue();
    }

    @Test
    void coachSuspension_sendsCoachSuspendedWithReasonAndAppealLink() {
        listener.onCoachSuspensionNotification(
            new CoachSuspensionNotificationEvent(this, UUID.randomUUID(), "repeated no-shows", "Coach Kim", TO));

        assertThat(sent(EmailTemplate.COACH_SUSPENDED))
            .containsEntry("reason", "repeated no-shows")
            .containsEntry("coachDisplayName", "Coach Kim")
            .containsEntry("profileUrl", "https://app.test/#/profile");
    }

    @Test
    void coachReinstated_sendsCoachReinstated() {
        listener.onCoachReinstated(new CoachReinstatedEvent(this, UUID.randomUUID(), 1L, "Coach Kim", TO));
        assertThat(sent(EmailTemplate.COACH_REINSTATED)).containsEntry("coachDisplayName", "Coach Kim");
    }

    @Test
    void coachWarning_sendsCoachWarningIssued() {
        listener.onCoachWarningIssued(
            new CoachWarningIssuedEvent(this, UUID.randomUUID(), UUID.randomUUID(), "SESSION_QUALITY", TO));
        assertThat(sent(EmailTemplate.COACH_WARNING_ISSUED)).containsEntry("disputeReason", "SESSION_QUALITY");
    }

    @Test
    void disputeResolved_formatsAmountAndInstantAsStrings() {
        Instant start = Instant.parse("2026-10-01T10:00:00Z");
        listener.onDisputeResolved(new DisputeResolvedEvent(this, UUID.randomUUID(), UUID.randomUUID(),
            "PARTIAL_CREDIT", 7L, new BigDecimal("12.5"), "note", "Coach Kim", start, TO));

        assertThat(sent(EmailTemplate.DISPUTE_RESOLVED))
            .containsEntry("resolution", "PARTIAL_CREDIT")
            .containsEntry("creditAmount", "12.50")
            .containsEntry("resolutionNote", "note")
            .containsEntry("requestedStartTime", "2026-10-01T10:00:00Z");
    }

    // feat/payment-forfeit-payouts-and-clawback
    private static final com.softropic.skillars.platform.admin.contract.UpholdCoachOutcome UPHELD =
        new com.softropic.skillars.platform.admin.contract.UpholdCoachOutcome(new java.math.BigDecimal("30.00"),
            new java.math.BigDecimal("12.00"), new java.math.BigDecimal("18.00"), new java.math.BigDecimal("27.00"),
            false, false);

    @Test
    void disputeResolved_upholdCoach_carriesTheClawbackAmounts() {
        listener.onDisputeResolved(new DisputeResolvedEvent(this, UUID.randomUUID(), UUID.randomUUID(),
            "UPHOLD_COACH", 1L, java.math.BigDecimal.ZERO, "happened", "Coach Kim", null, TO, UPHELD,
            com.softropic.skillars.platform.admin.contract.UpholdCoachOutcome.Audience.PARENT));

        assertThat(sent(EmailTemplate.DISPUTE_RESOLVED))
            .containsEntry("audience", "PARENT")
            .containsEntry("refundedAmount", "30.00")
            .containsEntry("recoveredAmount", "12.00")
            .containsEntry("absorbedAmount", "18.00")
            .containsEntry("coachPayoutAmount", "27.00")
            .containsEntry("packSession", "false");
    }

    @Test
    void disputeCoachUpheld_sendsTheCounterpartyNotice() {
        listener.onDisputeCoachUpheld(new com.softropic.skillars.platform.admin.contract.DisputeCoachUpheldEvent(
            UUID.randomUUID(), UUID.randomUUID(),
            com.softropic.skillars.platform.admin.contract.UpholdCoachOutcome.Audience.COACH, UPHELD, "Coach Kim",
            java.time.Instant.parse("2026-10-01T10:00:00Z"), TO));

        assertThat(sent(EmailTemplate.DISPUTE_COACH_UPHELD))
            .containsEntry("audience", "COACH")
            .containsEntry("coachPayoutAmount", "27.00")
            .containsEntry("requestedStartTime", "2026-10-01T10:00:00Z");
    }

    @Test
    void disputeDismissed_sendsDisputeDismissed() {
        listener.onDisputeDismissed(new DisputeDismissedEvent(UUID.randomUUID(), UUID.randomUUID(), 7L,
            "no evidence", "Coach Kim", null, TO));
        assertThat(sent(EmailTemplate.DISPUTE_DISMISSED))
            .containsEntry("reason", "no evidence")
            .containsEntry("requestedStartTime", "");
    }

    @Test
    void gdprExportReady_neverEmailsTheSignedDownloadUrl() {
        listener.onGdprExportReady(new GdprExportReadyEvent(this, UUID.randomUUID(), 7L,
            Instant.parse("2026-10-30T10:00:00Z"), TO));

        Map<String, Object> data = sent(EmailTemplate.GDPR_EXPORT_READY);
        assertThat(data).containsEntry("expiresAt", "2026-10-30T10:00:00Z")
            .containsEntry("profileUrl", "https://app.test/#/profile");
        assertThat(data.keySet()).noneMatch(k -> k.toLowerCase().contains("download"));
    }

    @Test
    void reviewApproved_andBlocked_mapToTheirTemplates_withCoachProfileLink() {
        UUID coachId = UUID.randomUUID();
        listener.onReviewModerationResolved(new ReviewModerationResolvedEvent(UUID.randomUUID(), coachId,
            ReviewModerationStatus.UNDER_REVIEW, ReviewModerationStatus.APPROVED, "to@x.test", "de", "Coach Kim"));
        assertThat(sent(EmailTemplate.REVIEW_APPROVED))
            .containsEntry("coachProfileUrl", "https://app.test/#/coaches/" + coachId);

        listener.onReviewModerationResolved(new ReviewModerationResolvedEvent(UUID.randomUUID(), coachId,
            ReviewModerationStatus.PENDING, ReviewModerationStatus.BLOCKED, "to@x.test", "de", "Coach Kim"));
        assertThat(sent(EmailTemplate.REVIEW_BLOCKED)).containsEntry("coachDisplayName", "Coach Kim");
    }

    @Test
    void noReachableRecipient_sendsNothing() {
        listener.onCoachReinstated(new CoachReinstatedEvent(this, UUID.randomUUID(), 1L));
        listener.onReviewModerationResolved(new ReviewModerationResolvedEvent(UUID.randomUUID(), UUID.randomUUID(),
            ReviewModerationStatus.PENDING, ReviewModerationStatus.BLOCKED));
        verifyNoInteractions(outbox);
    }

    @Test
    void enqueueFailure_isLoggedNotRethrown() {
        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
            .when(outbox).enqueueEmail(any(), any(), any(), anyString());
        listener.onCoachReinstated(new CoachReinstatedEvent(this, UUID.randomUUID(), 1L, "Coach Kim", TO));
    }
}
