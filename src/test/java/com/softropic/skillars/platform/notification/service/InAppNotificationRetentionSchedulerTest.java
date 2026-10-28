package com.softropic.skillars.platform.notification.service;

import com.softropic.skillars.platform.notification.repo.UserNotificationRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InAppNotificationRetentionSchedulerTest {

    @SuppressWarnings("unchecked")
    private static TransactionTemplate passThroughTx() {
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv ->
            ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        return tx;
    }

    @Test
    void loopsFullBatchesUntilAShortOne() {
        UserNotificationRepository repo = mock(UserNotificationRepository.class);
        when(repo.deleteCreatedBefore(any(), eq(InAppNotificationRetentionScheduler.BATCH_SIZE)))
            .thenReturn(InAppNotificationRetentionScheduler.BATCH_SIZE, InAppNotificationRetentionScheduler.BATCH_SIZE, 3);

        new InAppNotificationRetentionScheduler(repo, passThroughTx(), 90).purge();

        verify(repo, times(3)).deleteCreatedBefore(any(), anyInt());
    }

    @Test
    void retentionBelowTheFloor_isClamped() {
        UserNotificationRepository repo = mock(UserNotificationRepository.class);
        when(repo.deleteCreatedBefore(any(), anyInt())).thenReturn(0);

        new InAppNotificationRetentionScheduler(repo, passThroughTx(), 0).purge();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repo).deleteCreatedBefore(cutoff.capture(), anyInt());
        assertThat(cutoff.getValue()).isBefore(
            Instant.now().minus(Duration.ofDays(InAppNotificationRetentionScheduler.MIN_RETENTION_DAYS - 1)));
    }
}
