package com.softropic.skillars.platform.notification.infrastructure.listener;

import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.service.NotificationOutboxSupport;
import com.softropic.skillars.platform.video.contract.event.VideoModerationOwnerNotificationEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

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

/** B5: the owner "flagged" notice reaches a real address. */
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
        listener = new VideoOwnerEmailListener(outbox, transactionTemplate);
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
