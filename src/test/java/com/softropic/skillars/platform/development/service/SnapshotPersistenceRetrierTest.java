package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.repo.PlayerSkillStat;
import com.softropic.skillars.platform.development.repo.SnapshotBatchWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.TransactionSystemException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SnapshotPersistenceRetrierTest {

    private static final short ISO_YEAR = 2026;
    private static final short ISO_WEEK = 35;

    @Mock private SnapshotBatchWriter snapshotBatchWriter;

    private SnapshotPersistenceRetrier retrier;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        retrier = new SnapshotPersistenceRetrier(snapshotBatchWriter);
    }

    @Test
    void writeAllWithRetry_delegatesToWriter() {
        List<PlayerSkillStat> stats = List.of(new PlayerSkillStat());

        retrier.writeAllWithRetry(stats, ISO_YEAR, ISO_WEEK);

        verify(snapshotBatchWriter).writeAll(stats, ISO_YEAR, ISO_WEEK);
    }

    @Test
    void recoverSnapshotWriteFailure_logsAndDoesNotRethrow() {
        List<PlayerSkillStat> stats = List.of(new PlayerSkillStat());
        TransientDataAccessResourceException ex = new TransientDataAccessResourceException("db unavailable");

        // The @Recover method is the terminal handler after retries are exhausted — it must not
        // rethrow (matching the AOP contract: @Retryable's caller sees a clean return, not a
        // propagated exception), only log for ops visibility that the snapshot rows were lost.
        assertThatCode(() -> retrier.recoverSnapshotWriteFailure(ex, stats, ISO_YEAR, ISO_WEEK))
            .doesNotThrowAnyException();
    }

    @Test
    void recoverSnapshotWriteFailure_transactionException_logsAndDoesNotRethrow() {
        List<PlayerSkillStat> stats = List.of(new PlayerSkillStat());
        TransactionSystemException ex = new TransactionSystemException("commit failed");

        // The TransactionException @Recover overload is the terminal handler for a retried
        // transaction begin/commit failure — same no-rethrow contract as the DataAccessException one.
        assertThatCode(() -> retrier.recoverSnapshotWriteFailure(ex, stats, ISO_YEAR, ISO_WEEK))
            .doesNotThrowAnyException();
    }

    @Test
    void writeAllWithRetry_writerThrows_propagatesToCaller() {
        List<PlayerSkillStat> stats = List.of(new PlayerSkillStat());
        TransientDataAccessResourceException ex = new TransientDataAccessResourceException("db unavailable");
        doThrow(ex).when(snapshotBatchWriter).writeAll(stats, ISO_YEAR, ISO_WEEK);

        // Without the Spring AOP proxy (plain unit instantiation), @Retryable never intercepts —
        // this pins that the un-proxied method itself is a thin pass-through with no retry logic
        // baked into the method body, confirming retries come from the proxy, not self-invocation.
        assertThatThrownBy(() -> retrier.writeAllWithRetry(stats, ISO_YEAR, ISO_WEEK))
            .isSameAs(ex);
    }
}
