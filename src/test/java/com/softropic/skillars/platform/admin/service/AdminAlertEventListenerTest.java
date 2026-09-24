package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.platform.admin.contract.AdminAlertReferenceType;
import com.softropic.skillars.platform.admin.contract.AdminAlertStatus;
import com.softropic.skillars.platform.admin.contract.AdminAlertType;
import com.softropic.skillars.platform.admin.repo.AdminAlert;
import com.softropic.skillars.platform.admin.repo.AdminAlertRepository;
import com.softropic.skillars.platform.messaging.contract.ConversationReportedEvent;
import com.softropic.skillars.platform.messaging.contract.MessageHeldForReviewEvent;
import com.softropic.skillars.platform.messaging.contract.MessageReportedEvent;
import com.softropic.skillars.platform.payment.contract.event.CoachSubscriptionOrphanedEvent;
import com.softropic.skillars.platform.payment.contract.event.StrikeThresholdReachedEvent;
import com.softropic.skillars.platform.reviews.contract.ReviewFlaggedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-134 code review (2026-09-24), Finding #5: {@code PlatformTransactionManager} is
 * mocked here, so these tests can prove {@code insertAlert} <em>calls</em>
 * {@code saveAndFlush}/rolls back/re-throws correctly, but cannot prove a REAL transaction actually
 * commits or that another thread genuinely sees the result — a mock's {@code commit()}/{@code
 * rollback()} are no-ops. That is why {@code AdminAlertEventListenerConcurrencyIT} exists: it runs the
 * exact same code against a real Testcontainers Postgres with two genuinely concurrent callers, and is
 * the test that actually proves the fix (mutation-tested by hand, see its own Javadoc). This class is
 * for fast interaction-level coverage (which repository method is called, with what arguments, and
 * that a thrown exception is handled the way {@code TransactionTemplate} is documented to handle it —
 * see {@link #insertAlert_duplicateInsertRace_dataIntegrityViolationPropagatesUnwrapped()}), not for
 * proving transactional correctness.
 */
@ExtendWith(MockitoExtension.class)
class AdminAlertEventListenerTest {

    @Mock
    private AdminAlertRepository adminAlertRepository;

    // skillars-deferred-134 AC1: insertAlert now isolates its write in its own REQUIRES_NEW
    // transaction (mirrors GdprErasureServiceTest's own txManager/transactionStatus mocking pattern
    // for the same TransactionTemplate shape).
    @Mock
    private PlatformTransactionManager txManager;
    @Mock
    private TransactionStatus transactionStatus;

    @InjectMocks
    private AdminAlertEventListener listener;

    @BeforeEach
    void setUp() {
        when(adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(any(), any(), any()))
            .thenReturn(Optional.empty());
        lenient().when(txManager.getTransaction(any())).thenReturn(transactionStatus);
        // @PostConstruct is not invoked by Mockito's @InjectMocks — call it directly, same as
        // GdprErasureServiceTest's own service.initTemplates() call after construction.
        listener.initTemplate();
    }

    @Test
    void onMessageReported_insertsAlert() {
        long messageId = 12345L;
        listener.onMessageReported(new MessageReportedEvent(1L, messageId, 99L, 55L, "SPAM"));

        ArgumentCaptor<AdminAlert> captor = ArgumentCaptor.forClass(AdminAlert.class);
        verify(adminAlertRepository).saveAndFlush(captor.capture());
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
        verify(adminAlertRepository).saveAndFlush(captor.capture());
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
        verify(adminAlertRepository).saveAndFlush(captor.capture());
        AdminAlert saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(AdminAlertType.REVIEW_FLAG);
        assertThat(saved.getReferenceId()).isEqualTo(reviewId.toString());
        assertThat(saved.getReferenceType()).isEqualTo(AdminAlertReferenceType.REVIEW);
    }

    @Test
    void onMessageHeldForReview_insertsModerationUnresolvedAlert() {
        long messageId = 54321L;
        listener.onMessageHeldForReview(new MessageHeldForReviewEvent(messageId, 77L, "MODERATION_ORPHAN_SWEPT"));

        ArgumentCaptor<AdminAlert> captor = ArgumentCaptor.forClass(AdminAlert.class);
        verify(adminAlertRepository).saveAndFlush(captor.capture());
        AdminAlert saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(AdminAlertType.MODERATION_UNRESOLVED);
        assertThat(saved.getReferenceId()).isEqualTo(String.valueOf(messageId));
        assertThat(saved.getReferenceType()).isEqualTo(AdminAlertReferenceType.MESSAGE);
    }

    @Test
    void onStrikeThreshold_insertsAlert() {
        UUID coachId = UUID.randomUUID();
        listener.onStrikeThreshold(new StrikeThresholdReachedEvent(this, coachId, UUID.randomUUID(), 3L));

        ArgumentCaptor<AdminAlert> captor = ArgumentCaptor.forClass(AdminAlert.class);
        verify(adminAlertRepository).saveAndFlush(captor.capture());
        AdminAlert saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(AdminAlertType.STRIKE_THRESHOLD);
        assertThat(saved.getReferenceId()).isEqualTo(coachId.toString());
        assertThat(saved.getReferenceType()).isEqualTo(AdminAlertReferenceType.COACH);
    }

    /** skillars-deferred-133 AC3. */
    @Test
    void onCoachSubscriptionOrphaned_insertsAlert() {
        UUID coachProfileId = UUID.randomUUID();
        listener.onCoachSubscriptionOrphaned(
            new CoachSubscriptionOrphanedEvent(this, coachProfileId, "sub_test_001"));

        ArgumentCaptor<AdminAlert> captor = ArgumentCaptor.forClass(AdminAlert.class);
        verify(adminAlertRepository).saveAndFlush(captor.capture());
        AdminAlert saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(AdminAlertType.SUBSCRIPTION_ORPHANED);
        assertThat(saved.getReferenceId()).isEqualTo(coachProfileId.toString());
        assertThat(saved.getReferenceType()).isEqualTo(AdminAlertReferenceType.COACH);
    }

    /**
     * skillars-deferred-134 code review (2026-09-24), Finding #4: proves — without needing a real
     * transaction manager — that {@code TransactionTemplate.execute()} does not wrap a
     * {@code DataIntegrityViolationException} thrown from inside its callback as {@code
     * TransactionSystemException} or anything else. This is {@code TransactionTemplate}'s OWN control
     * flow (catch the callback's exception, call {@code rollback(status)}, then {@code throw ex;} —
     * the identical exception instance), independent of whether the underlying manager is real or
     * mocked, so a mocked {@code txManager} is a legitimate way to pin this specific assumption; it is
     * {@code AdminAlertEventListenerConcurrencyIT}'s job (not this test's) to additionally prove the
     * real-Postgres flush-time translation this method also relies on.
     */
    @Test
    void insertAlert_duplicateInsertRace_dataIntegrityViolationPropagatesUnwrapped() {
        DataIntegrityViolationException duplicateKeyViolation =
            new DataIntegrityViolationException("duplicate key value violates unique constraint");
        when(adminAlertRepository.saveAndFlush(any())).thenThrow(duplicateKeyViolation);

        // Must not throw -- if TransactionTemplate re-wrapped the exception (e.g. as
        // TransactionSystemException), insertAlert's own catch (DataIntegrityViolationException)
        // would miss it and this call would throw instead of gracefully suppressing the duplicate.
        listener.onMessageReported(new MessageReportedEvent(1L, 12345L, 99L, 55L, "SPAM"));

        verify(adminAlertRepository).saveAndFlush(any());
        verify(txManager).rollback(any());
        verify(txManager, never()).commit(any());
    }

    @Test
    void duplicateEvent_skipsInsert() {
        when(adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(any(), any(), any()))
            .thenReturn(Optional.of(new AdminAlert()));

        listener.onMessageReported(new MessageReportedEvent(1L, 12345L, 99L, 55L, "SPAM"));

        verify(adminAlertRepository, never()).saveAndFlush(any());
    }
}
