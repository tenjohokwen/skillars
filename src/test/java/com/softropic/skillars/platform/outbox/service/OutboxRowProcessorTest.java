package com.softropic.skillars.platform.outbox.service;

import com.softropic.skillars.platform.outbox.contract.OutboxMessageHandler;
import com.softropic.skillars.platform.outbox.repo.OutboxMessage;
import com.softropic.skillars.platform.outbox.repo.OutboxMessageRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-91 AC1 + code review decisions D2/D6 — unit coverage for
 * {@link OutboxRowProcessor}: single-row claim, dispatch by {@code aggregate_type},
 * delete-on-success, failure carried out through the rollback, {@code attempts++} recorded in a
 * separate transaction, exponential backoff, and the "no handler → row is kept, never dropped" rule.
 */
class OutboxRowProcessorTest {

    private OutboxMessageRepository repository;
    private OutboxMessageHandler moneyHandler;
    private OutboxRowProcessor processor;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxMessageRepository.class);
        moneyHandler = mock(OutboxMessageHandler.class);
        when(moneyHandler.aggregateType()).thenReturn("MONEY");
        processor = new OutboxRowProcessor(repository, List.of(moneyHandler));
    }

    private static OutboxMessage row(long id, String aggregateType, int attempts) {
        OutboxMessage m = new OutboxMessage(aggregateType, "{\"id\":" + id + "}");
        m.setId(id);
        m.setAttempts(attempts);
        return m;
    }

    @Test
    void claimAndHandle_claimsExactlyOneDueRow() {
        when(repository.claimNextDue(any(), any())).thenReturn(List.of());

        assertThat(processor.claimAndHandle()).isEqualTo(OutboxRowProcessor.Outcome.NONE);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).claimNextDue(any(Instant.class), page.capture());
        assertThat(page.getValue()).isEqualTo(PageRequest.of(0, 1));
    }

    @Test
    void claimAndHandle_dispatchesToItsHandlerAndDeletesOnSuccess() {
        OutboxMessage a = row(1, "MONEY", 0);
        when(repository.claimNextDue(any(), any())).thenReturn(List.of(a));

        assertThat(processor.claimAndHandle()).isEqualTo(OutboxRowProcessor.Outcome.PROCESSED);

        verify(moneyHandler).handle(a.getPayload());
        verify(repository).delete(a);
    }

    @Test
    void claimAndHandle_handlerThrows_carriesTheRowIdOutThroughTheRollback() {
        OutboxMessage a = row(7, "MONEY", 3);
        when(repository.claimNextDue(any(), any())).thenReturn(List.of(a));
        org.mockito.Mockito.doThrow(new RuntimeException("gateway 503")).when(moneyHandler).handle(any());

        assertThatThrownBy(() -> processor.claimAndHandle())
            .isInstanceOf(OutboxRowProcessor.OutboxRowFailure.class)
            .satisfies(e -> {
                assertThat(((OutboxRowProcessor.OutboxRowFailure) e).getRowId()).isEqualTo(7L);
                assertThat(((OutboxRowProcessor.OutboxRowFailure) e).getAggregateType()).isEqualTo("MONEY");
            })
            .hasRootCauseMessage("gateway 503");

        // Bookkeeping is NOT done here — that would be the poisoned transaction the review removed.
        verify(repository, never()).save(any());
        verify(repository, never()).delete(a);
    }

    @Test
    void claimAndHandle_noHandlerForAggregateType_failsRatherThanDroppingTheRow() {
        OutboxMessage orphan = row(9, "SOME_FUTURE_TYPE", 0);
        when(repository.claimNextDue(any(), any())).thenReturn(List.of(orphan));

        assertThatThrownBy(() -> processor.claimAndHandle())
            .isInstanceOf(OutboxRowProcessor.OutboxRowFailure.class)
            .hasRootCauseMessage("no OutboxMessageHandler registered for aggregate_type=SOME_FUTURE_TYPE");

        verify(repository, never()).delete(orphan);
    }

    @Test
    void recordFailure_incrementsAttempts_recordsError_andSchedulesABackoff() {
        OutboxMessage a = row(1, "MONEY", 3);
        when(repository.findById(1L)).thenReturn(Optional.of(a));
        Instant before = Instant.now();

        processor.recordFailure(1L, new RuntimeException("gateway 503"));

        assertThat(a.getAttempts()).isEqualTo(4);
        assertThat(a.getLastError()).contains("gateway 503");
        assertThat(a.getNextAttemptAt()).isAfter(before);
        verify(repository).save(a);
        verify(repository, never()).delete(a);
    }

    @Test
    void recordFailure_onAnAlreadyDeletedRow_isANoOp() {
        when(repository.findById(42L)).thenReturn(Optional.empty());

        processor.recordFailure(42L, new RuntimeException("boom"));

        verify(repository, never()).save(any());
    }

    @Test
    void backoff_growsExponentiallyAndIsCappedAtOneHour() {
        assertThat(OutboxRowProcessor.backoffFor(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(OutboxRowProcessor.backoffFor(2)).isEqualTo(Duration.ofMinutes(1));
        assertThat(OutboxRowProcessor.backoffFor(3)).isEqualTo(Duration.ofMinutes(2));
        assertThat(OutboxRowProcessor.backoffFor(4)).isEqualTo(Duration.ofMinutes(4));
        // Capped — a stuck row must not drift to a retry interval measured in days.
        assertThat(OutboxRowProcessor.backoffFor(10)).isEqualTo(Duration.ofHours(1));
        assertThat(OutboxRowProcessor.backoffFor(64)).isEqualTo(Duration.ofHours(1));
    }
}
