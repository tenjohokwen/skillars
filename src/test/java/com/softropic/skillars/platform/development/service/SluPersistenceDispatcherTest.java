package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.repo.PlayerSkillStat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Async;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class SluPersistenceDispatcherTest {

    private static final short ISO_YEAR = 2026;
    private static final short ISO_WEEK = 35;

    @Mock private SluPersistenceRetrier sluPersistenceRetrier;
    @Mock private SnapshotPersistenceRetrier snapshotPersistenceRetrier;

    @Test
    void dispatchSluPersistence_delegatesToSaveThenSnapshot_inOrder_sameArgs() {
        SluPersistenceDispatcher dispatcher =
            new SluPersistenceDispatcher(sluPersistenceRetrier, snapshotPersistenceRetrier);
        List<PlayerSkillStat> stats = List.of(new PlayerSkillStat());

        dispatcher.dispatchSluPersistence(stats, ISO_YEAR, ISO_WEEK);

        // One chained task (M3): save first, then snapshot — same list, same instances, one thread.
        InOrder inOrder = inOrder(sluPersistenceRetrier, snapshotPersistenceRetrier);
        inOrder.verify(sluPersistenceRetrier).saveSluWithRetry(stats);
        inOrder.verify(snapshotPersistenceRetrier).writeAllWithRetry(stats, ISO_YEAR, ISO_WEEK);
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    void dispatchSluPersistence_isBoundToTheIsolatedSluRetryExecutor() throws NoSuchMethodException {
        // Regression guard (AC3): the dispatch must run on the dedicated bounded pool, never the
        // shared default @Async listener pool.
        Async async = SluPersistenceDispatcher.class
            .getMethod("dispatchSluPersistence", List.class, short.class, short.class)
            .getAnnotation(Async.class);

        assertThat(async).isNotNull();
        assertThat(async.value()).isEqualTo("sluRetryExecutor");
    }
}
