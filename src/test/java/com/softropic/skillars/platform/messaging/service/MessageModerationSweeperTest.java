package com.softropic.skillars.platform.messaging.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.messaging.contract.MessageHeldForReviewEvent;
import com.softropic.skillars.platform.messaging.contract.MessageModerationStatus;
import com.softropic.skillars.platform.messaging.repo.Message;
import com.softropic.skillars.platform.messaging.repo.MessageRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageModerationSweeperTest {

    @Mock MessageRepository messageRepository;
    @Mock ConfigService configService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock TransactionTemplate transactionTemplate;

    private MessageModerationSweeper sweeper;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        // Hand-constructed, not @InjectMocks — @InjectMocks does not run @PostConstruct, and more
        // importantly here we need transactionTemplate.execute/executeWithoutResult to actually
        // invoke the passed callback against our mocks (the repo-wide scheduler-test convention).
        sweeper = new MessageModerationSweeper(
            messageRepository, configService, eventPublisher, transactionTemplate, meterRegistry);

        lenient().when(configService.getBoundedLong(eq(MessageModerationSweeper.GRACE_MINUTES_KEY), anyLong(), anyLong(), anyLong()))
            .thenReturn(30L);

        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
        lenient().doAnswer(inv -> {
            Consumer<TransactionStatus> cb = inv.getArgument(0);
            cb.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private Message pendingMessage() {
        Message m = new Message();
        m.setId(1L);
        m.setConversationId(10L);
        m.setSenderId(20L);
        m.setSenderRole(com.softropic.skillars.platform.messaging.contract.SenderRole.COACH);
        m.setContent("stranded");
        m.setModerationStatus(MessageModerationStatus.PENDING);
        m.setCreatedAt(Instant.now().minusSeconds(3600));
        return m;
    }

    @Test
    void sweep_noStrandedMessages_doesNothing() {
        when(messageRepository.findPendingOlderThan(any(), any())).thenReturn(List.of());

        sweeper.sweep();

        verify(messageRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void sweep_strandedPending_transitionsToUnderReview_publishesEventAndPublishesOnce() {
        Message stranded = pendingMessage();
        when(messageRepository.findPendingOlderThan(any(), any())).thenReturn(List.of(stranded));
        when(messageRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(stranded));

        sweeper.sweep();

        assertThat(stranded.getModerationStatus()).isEqualTo(MessageModerationStatus.UNDER_REVIEW);
        verify(messageRepository).save(stranded);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(MessageHeldForReviewEvent.class);
        MessageHeldForReviewEvent event = (MessageHeldForReviewEvent) eventCaptor.getValue();
        assertThat(event.messageId()).isEqualTo(1L);
        assertThat(event.conversationId()).isEqualTo(10L);
    }

    @Test
    void sweep_messageAlreadyResolvedBetweenSelectAndSweep_leftUntouched_noAlert() {
        Message stranded = pendingMessage();
        Message reReadApproved = pendingMessage();
        reReadApproved.setModerationStatus(MessageModerationStatus.APPROVED);
        when(messageRepository.findPendingOlderThan(any(), any())).thenReturn(List.of(stranded));
        when(messageRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(reReadApproved));

        sweeper.sweep();

        verify(messageRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void sweep_messageSoftDeletedBetweenSelectAndSweep_leftUntouched_noAlert() {
        Message stranded = pendingMessage();
        Message reReadDeleted = pendingMessage();
        reReadDeleted.setDeletedAt(Instant.now());
        when(messageRepository.findPendingOlderThan(any(), any())).thenReturn(List.of(stranded));
        when(messageRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(reReadDeleted));

        sweeper.sweep();

        verify(messageRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ── Code review 2026-08-05 ──

    @Test
    void sweep_selectIsCappedAtMaxBatch() {
        when(messageRepository.findPendingOlderThan(any(), any())).thenReturn(List.of());

        sweeper.sweep();

        ArgumentCaptor<org.springframework.data.domain.Pageable> pageCaptor =
            ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(messageRepository).findPendingOlderThan(any(), pageCaptor.capture());
        assertThat(pageCaptor.getValue().getPageSize()).isEqualTo(MessageModerationSweeper.MAX_BATCH);
    }

    @Test
    void sweep_alertPublishFails_messageIsStillRecovered() {
        // The alert listener runs REQUIRES_NEW and the publish is wrapped, so a failure to raise
        // the admin alert must not bounce the message back to PENDING — that stranding is the
        // exact failure this sweeper exists to end.
        Message stranded = pendingMessage();
        when(messageRepository.findPendingOlderThan(any(), any())).thenReturn(List.of(stranded));
        when(messageRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(stranded));
        org.mockito.Mockito.doThrow(new RuntimeException("admin_alerts insert failed"))
            .when(eventPublisher).publishEvent(any(MessageHeldForReviewEvent.class));

        sweeper.sweep();

        assertThat(stranded.getModerationStatus()).isEqualTo(MessageModerationStatus.UNDER_REVIEW);
        verify(messageRepository).save(stranded);
        assertThat(meterRegistry.counter("messaging.moderation.swept").count()).isEqualTo(1.0);
    }

    @Test
    void sweep_transactionFails_counterNotIncremented() {
        // The counter is incremented after the transaction returns, not inside it: counting a
        // rolled-back sweep would over-report during exactly the outage this metric is watched for.
        Message stranded = pendingMessage();
        when(messageRepository.findPendingOlderThan(any(), any())).thenReturn(List.of(stranded));
        when(messageRepository.findByIdForUpdate(1L)).thenThrow(new RuntimeException("boom"));

        sweeper.sweep();

        assertThat(meterRegistry.counter("messaging.moderation.swept").count()).isZero();
    }

    @Test
    void sweep_guardFires_counterNotIncremented() {
        Message stranded = pendingMessage();
        Message reReadApproved = pendingMessage();
        reReadApproved.setModerationStatus(MessageModerationStatus.APPROVED);
        when(messageRepository.findPendingOlderThan(any(), any())).thenReturn(List.of(stranded));
        when(messageRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(reReadApproved));

        sweeper.sweep();

        assertThat(meterRegistry.counter("messaging.moderation.swept").count()).isZero();
    }

    @Test
    void sweep_publishesSweptReasonNotUncertain() {
        Message stranded = pendingMessage();
        when(messageRepository.findPendingOlderThan(any(), any())).thenReturn(List.of(stranded));
        when(messageRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(stranded));

        sweeper.sweep();

        ArgumentCaptor<MessageHeldForReviewEvent> captor =
            ArgumentCaptor.forClass(MessageHeldForReviewEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        // The reason distinguishes "never moderated at all" from ModerationResultApplier's
        // "classifier ran and was unsure" — the admin queue renders it, so it must not drift.
        assertThat(captor.getValue().reason()).isEqualTo(MessageModerationSweeper.HELD_REASON_SWEPT);
    }

    @Test
    void sweep_oneMessageFailsDuringSweep_doesNotAbortOthers() {
        Message stranded1 = pendingMessage();
        Message stranded2 = pendingMessage();
        stranded2.setId(2L);
        when(messageRepository.findPendingOlderThan(any(), any())).thenReturn(List.of(stranded1, stranded2));
        when(messageRepository.findByIdForUpdate(1L)).thenThrow(new RuntimeException("boom"));
        when(messageRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(stranded2));

        sweeper.sweep();

        assertThat(stranded2.getModerationStatus()).isEqualTo(MessageModerationStatus.UNDER_REVIEW);
        verify(messageRepository).save(stranded2);
    }
}
