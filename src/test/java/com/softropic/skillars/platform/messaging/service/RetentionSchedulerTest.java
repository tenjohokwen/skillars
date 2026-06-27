package com.softropic.skillars.platform.messaging.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.messaging.repo.ConversationRepository;
import com.softropic.skillars.platform.messaging.repo.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetentionSchedulerTest {

    @Mock MessageRepository messageRepository;
    @Mock ConversationRepository conversationRepository;
    @Mock ConfigService configService;

    MessageRetentionScheduler scheduler;

    @BeforeEach
    void setUp() {
        TransactionTemplate txTemplate = new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
        scheduler = new MessageRetentionScheduler(messageRepository, conversationRepository, configService, txTemplate);
        when(configService.getInt("platform.message_retention_months", 24)).thenReturn(24);
    }

    @Test
    void runRetention_deletesExpiredMessagesAndOrphanConversations() {
        when(messageRepository.deleteExpiredMessages(any())).thenReturn(5);
        when(conversationRepository.deleteOrphanConversations(any())).thenReturn(2);

        scheduler.runRetention();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(messageRepository).deleteExpiredMessages(cutoffCaptor.capture());
        assertThat(cutoffCaptor.getValue()).isBefore(Instant.now().minus(700, ChronoUnit.DAYS));
        verify(conversationRepository).deleteOrphanConversations(any());
    }

    @Test
    void runRetention_dbErrorCaught_noRethrow() {
        when(messageRepository.deleteExpiredMessages(any())).thenThrow(new RuntimeException("DB error"));

        scheduler.runRetention();

        verify(conversationRepository, never()).deleteOrphanConversations(any());
    }

    @Test
    void runRetention_usesConfiguredRetentionPeriod() {
        when(configService.getInt("platform.message_retention_months", 24)).thenReturn(12);
        when(messageRepository.deleteExpiredMessages(any())).thenReturn(0);
        when(conversationRepository.deleteOrphanConversations(any())).thenReturn(0);

        scheduler.runRetention();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(messageRepository).deleteExpiredMessages(cutoffCaptor.capture());
        Instant cutoff = cutoffCaptor.getValue();

        // 12 calendar months ago — use month-based bounds to tolerate leap year variation (365 vs 366 days)
        assertThat(cutoff)
            .isAfter(Instant.now().atZone(ZoneOffset.UTC).minusMonths(13).toInstant())
            .isBefore(Instant.now().atZone(ZoneOffset.UTC).minusMonths(11).toInstant());
    }
}
