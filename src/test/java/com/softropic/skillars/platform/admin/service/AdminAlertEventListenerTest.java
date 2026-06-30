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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAlertEventListenerTest {

    @Mock
    private AdminAlertRepository adminAlertRepository;

    @InjectMocks
    private AdminAlertEventListener listener;

    @BeforeEach
    void setUp() {
        when(adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(any(), any(), any()))
            .thenReturn(Optional.empty());
    }

    @Test
    void onMessageReported_insertsAlert() {
        long messageId = 12345L;
        listener.onMessageReported(new MessageReportedEvent(1L, messageId, 99L, 55L, "SPAM"));

        ArgumentCaptor<AdminAlert> captor = ArgumentCaptor.forClass(AdminAlert.class);
        verify(adminAlertRepository).save(captor.capture());
        AdminAlert saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(AdminAlertType.MESSAGE_REPORT);
        assertThat(saved.getReferenceId()).isEqualTo(String.valueOf(messageId));
        assertThat(saved.getReferenceType()).isEqualTo(AdminAlertReferenceType.MESSAGE);
    }

    @Test
    void onConversationReported_insertsAlert() {
        long conversationId = 67890L;
        listener.onConversationReported(new ConversationReportedEvent(2L, conversationId, 55L, "INAPPROPRIATE_CONTENT"));

        ArgumentCaptor<AdminAlert> captor = ArgumentCaptor.forClass(AdminAlert.class);
        verify(adminAlertRepository).save(captor.capture());
        AdminAlert saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(AdminAlertType.CONVERSATION_REPORT);
        assertThat(saved.getReferenceId()).isEqualTo(String.valueOf(conversationId));
        assertThat(saved.getReferenceType()).isEqualTo(AdminAlertReferenceType.CONVERSATION);
    }

    @Test
    void onReviewFlagged_insertsAlert() {
        UUID reviewId = UUID.randomUUID();
        listener.onReviewFlagged(new ReviewFlaggedEvent(reviewId, UUID.randomUUID(), 3L, true));

        ArgumentCaptor<AdminAlert> captor = ArgumentCaptor.forClass(AdminAlert.class);
        verify(adminAlertRepository).save(captor.capture());
        AdminAlert saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(AdminAlertType.REVIEW_FLAG);
        assertThat(saved.getReferenceId()).isEqualTo(reviewId.toString());
        assertThat(saved.getReferenceType()).isEqualTo(AdminAlertReferenceType.REVIEW);
    }

    @Test
    void onStrikeThreshold_insertsAlert() {
        UUID coachId = UUID.randomUUID();
        listener.onStrikeThreshold(new StrikeThresholdReachedEvent(this, coachId, UUID.randomUUID(), 3L));

        ArgumentCaptor<AdminAlert> captor = ArgumentCaptor.forClass(AdminAlert.class);
        verify(adminAlertRepository).save(captor.capture());
        AdminAlert saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(AdminAlertType.STRIKE_THRESHOLD);
        assertThat(saved.getReferenceId()).isEqualTo(coachId.toString());
        assertThat(saved.getReferenceType()).isEqualTo(AdminAlertReferenceType.COACH);
    }

    @Test
    void duplicateEvent_skipsInsert() {
        when(adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(any(), any(), any()))
            .thenReturn(Optional.of(new AdminAlert()));

        listener.onMessageReported(new MessageReportedEvent(1L, 12345L, 99L, 55L, "SPAM"));

        verify(adminAlertRepository, never()).save(any());
    }
}
