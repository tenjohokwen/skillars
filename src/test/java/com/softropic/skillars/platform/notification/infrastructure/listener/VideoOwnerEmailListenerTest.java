package com.softropic.skillars.platform.notification.infrastructure.listener;

import com.softropic.skillars.infrastructure.web.FrontendLinkBuilder;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.service.NotificationOutboxSupport;
import com.softropic.skillars.platform.video.contract.event.VideoApprovalOwnerNotificationEvent;
import com.softropic.skillars.platform.video.contract.event.VideoApprovalParentNotificationEvent;
import com.softropic.skillars.platform.video.contract.event.VideoModerationOwnerNotificationEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** B5: the minor-safety approval gate and the owner "flagged" notice reach a real address. */
class VideoOwnerEmailListenerTest {

    private NotificationOutboxSupport outbox;
    private TransactionTemplate transactionTemplate;
    private VideoOwnerEmailListener listener;

    private final UUID videoId = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        outbox = mock(NotificationOutboxSupport.class);
        transactionTemplate = mock(TransactionTemplate.class);
        doAnswer(inv -> {
            ((Consumer<org.springframework.transaction.TransactionStatus>) inv.getArgument(0)).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        listener = new VideoOwnerEmailListener(outbox, new FrontendLinkBuilder("https://app.skillars.test/"),
            transactionTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void approvalRequested_emailsParentWithApprovalsLink() {
        listener.onApprovalRequested(new VideoApprovalParentNotificationEvent(
            videoId, 11L, 22L, "Mia", "HOMEWORK", Instant.now(), "parent@example.com", "fr"));

        ArgumentCaptor<Recipient> recipient = ArgumentCaptor.forClass(Recipient.class);
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(outbox).enqueueEmail(eq(EmailTemplate.VIDEO_APPROVAL_REQUESTED), recipient.capture(), data.capture(), anyString());
        assertThat(recipient.getValue().getEmail()).isEqualTo("parent@example.com");
        assertThat(recipient.getValue().getLangKey()).isEqualTo("fr");
        assertThat(data.getValue())
            .containsEntry("playerName", "Mia")
            .containsEntry("videoType", "HOMEWORK")
            .containsEntry("approvalsUrl", "https://app.skillars.test/#/parent/approvals");
        // String-typed email data contract (outbox JSON round-trip).
        assertThat(data.getValue().values()).allMatch(String.class::isInstance);
    }

    @Test
    void approvalRequested_parentWithoutEmail_isSkippedNotSentToANonAddress() {
        listener.onApprovalRequested(new VideoApprovalParentNotificationEvent(
            videoId, 11L, 22L, "Mia", "HOMEWORK", Instant.now(), null, null));

        verify(outbox, never()).enqueueEmail(any(), any(), anyMap(), anyString());
    }

    @Test
    void decision_mapsToApprovedAndRejectedTemplates() {
        listener.onApprovalDecided(new VideoApprovalOwnerNotificationEvent(videoId, 11L, "approved", "kid@example.com", null));
        listener.onApprovalDecided(new VideoApprovalOwnerNotificationEvent(videoId, 11L, "rejected", "kid@example.com", null));

        verify(outbox).enqueueEmail(eq(EmailTemplate.VIDEO_APPROVAL_APPROVED), any(), anyMap(), anyString());
        verify(outbox).enqueueEmail(eq(EmailTemplate.VIDEO_APPROVAL_REJECTED), any(), anyMap(), anyString());
    }

    @Test
    void decision_playerWithoutEmail_isSkipped() {
        listener.onApprovalDecided(new VideoApprovalOwnerNotificationEvent(videoId, 11L, "approved", " ", null));

        verify(outbox, never()).enqueueEmail(any(), any(), anyMap(), anyString());
    }

    @Test
    void moderationNotice_goesThroughOutboxInItsOwnTransaction_toResolvedRecipient() {
        listener.onModerationOwnerNotice(new VideoModerationOwnerNotificationEvent(
            videoId, "555000001", "Your video has been flagged for review", "kid@example.com", "de"));

        verify(transactionTemplate).executeWithoutResult(any());
        ArgumentCaptor<Recipient> recipient = ArgumentCaptor.forClass(Recipient.class);
        verify(outbox).enqueueEmail(eq(EmailTemplate.VIDEO_MODERATION_OWNER_FLAGGED), recipient.capture(), anyMap(), anyString());
        assertThat(recipient.getValue().getEmail()).isEqualTo("kid@example.com");
    }

    @Test
    void moderationNotice_enqueueFailure_neverPropagatesToThePipeline() {
        doThrow(new IllegalStateException("db down")).when(outbox).enqueueEmail(any(), any(), anyMap(), anyString());

        assertThatCode(() -> listener.onModerationOwnerNotice(new VideoModerationOwnerNotificationEvent(
            videoId, "555000001", "flagged", "kid@example.com", null))).doesNotThrowAnyException();
    }

    @Test
    void moderationNotice_unreachableOwner_isSkipped() {
        listener.onModerationOwnerNotice(new VideoModerationOwnerNotificationEvent(
            videoId, "555000001", "flagged", null, null));

        verify(transactionTemplate, never()).executeWithoutResult(any());
        verify(outbox, never()).enqueueEmail(any(), any(), anyMap(), anyString());
    }
}
