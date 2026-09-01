package com.softropic.skillars.platform.development.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.softropic.skillars.platform.development.repo.PlayerSkillStat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SluPersistenceDispatcherTest {

    private static final short ISO_YEAR = 2026;
    private static final short ISO_WEEK = 35;

    @Mock private SluPersistenceRetrier sluPersistenceRetrier;
    @Mock private SnapshotPersistenceRetrier snapshotPersistenceRetrier;

    private SluPersistenceDispatcher dispatcher;
    private Logger dispatcherLogger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> logCapture;

    @BeforeEach
    void setUp() {
        dispatcher = new SluPersistenceDispatcher(sluPersistenceRetrier, snapshotPersistenceRetrier);
        dispatcherLogger = (Logger) LoggerFactory.getLogger(SluPersistenceDispatcher.class);
        originalLevel = dispatcherLogger.getLevel();
        dispatcherLogger.setLevel(Level.INFO);   // the "chain finished" line is INFO
        logCapture = new ListAppender<>();
        logCapture.start();
        dispatcherLogger.addAppender(logCapture);
    }

    @AfterEach
    void tearDown() {
        dispatcherLogger.detachAppender(logCapture);
        dispatcherLogger.setLevel(originalLevel);
    }

    @Test
    void dispatchSluPersistence_sluSavePersisted_writesSnapshotThenLogsFinished() {
        List<PlayerSkillStat> stats = List.of(new PlayerSkillStat());
        when(sluPersistenceRetrier.saveSluWithRetry(stats)).thenReturn(SluSaveOutcome.SAVED);

        dispatcher.dispatchSluPersistence(stats, ISO_YEAR, ISO_WEEK);

        // One chained task: save first, then snapshot — same list, same instances, one thread.
        InOrder inOrder = inOrder(sluPersistenceRetrier, snapshotPersistenceRetrier);
        inOrder.verify(sluPersistenceRetrier).saveSluWithRetry(stats);
        inOrder.verify(snapshotPersistenceRetrier).writeAllWithRetry(stats, ISO_YEAR, ISO_WEEK);
        inOrder.verifyNoMoreInteractions();
        assertThat(logCapture.list)
            .anyMatch(e -> e.getFormattedMessage().contains("SLU persistence chain finished"));
    }

    @Test
    void dispatchSluPersistence_sluSaveExhausted_skipsSnapshotWrite() {
        // skillars-deferred-89 AC2: saveSluWithRetry returns FAILED only from its @Recover paths
        // (retries exhausted). The weekly-snapshot write MUST be skipped so player_slu_weekly_snapshot
        // cannot gain a total the (now absent) detail rows do not back.
        List<PlayerSkillStat> stats = List.of(new PlayerSkillStat());
        when(sluPersistenceRetrier.saveSluWithRetry(stats)).thenReturn(SluSaveOutcome.FAILED);

        dispatcher.dispatchSluPersistence(stats, ISO_YEAR, ISO_WEEK);

        // Mutation check: reverting the dispatcher to an unconditional writeAllWithRetry call fails
        // this verification.
        verify(snapshotPersistenceRetrier, never()).writeAllWithRetry(stats, ISO_YEAR, ISO_WEEK);
        assertThat(logCapture.list)
            .anyMatch(e -> e.getLevel() == Level.ERROR
                && e.getFormattedMessage().contains("skipping the weekly-snapshot write"));
        assertThat(logCapture.list)
            .noneMatch(e -> e.getFormattedMessage().contains("SLU persistence chain finished"));
    }

    @Test
    void dispatchSluPersistence_rowsAlreadyPersistedByConcurrentDelivery_skipsSnapshotWrite_noError() {
        // skillars-deferred-89 AC2 (code review): when saveSluWithRetry reports ALREADY_PERSISTED
        // (existsBySessionId short-circuit or the V47 collision catch), the winning delivery owns the
        // snapshot write for the bucket the rows belong to. This task's iso-week can differ across a
        // week boundary, so running writeAllWithRetry here would over-report. It must be skipped —
        // quietly, NOT as an error (nothing was lost).
        List<PlayerSkillStat> stats = List.of(new PlayerSkillStat());
        when(sluPersistenceRetrier.saveSluWithRetry(stats)).thenReturn(SluSaveOutcome.ALREADY_PERSISTED);

        dispatcher.dispatchSluPersistence(stats, ISO_YEAR, ISO_WEEK);

        verify(snapshotPersistenceRetrier, never()).writeAllWithRetry(stats, ISO_YEAR, ISO_WEEK);
        assertThat(logCapture.list)
            .noneMatch(e -> e.getLevel() == Level.ERROR);
        assertThat(logCapture.list)
            .anyMatch(e -> e.getFormattedMessage().contains("already persisted by a concurrent delivery"));
        assertThat(logCapture.list)
            .noneMatch(e -> e.getFormattedMessage().contains("SLU persistence chain finished"));
    }

    @Test
    void dispatchSluPersistence_isBoundToTheIsolatedSluRetryExecutor() throws NoSuchMethodException {
        // Regression guard: the dispatch must run on the dedicated bounded pool, never the shared
        // default @Async listener pool.
        Async async = SluPersistenceDispatcher.class
            .getMethod("dispatchSluPersistence", List.class, short.class, short.class)
            .getAnnotation(Async.class);

        assertThat(async).isNotNull();
        assertThat(async.value()).isEqualTo("sluRetryExecutor");
    }
}
